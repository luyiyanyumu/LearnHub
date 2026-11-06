"""Bounded, image-only adapter for the fixed offline Tesseract command."""
from __future__ import annotations

from collections import OrderedDict
from io import BytesIO
import math
import re
import subprocess
import threading
import time
import warnings

from PIL import Image, PngImagePlugin, UnidentifiedImageError

MAX_INPUT_BYTES = 12 * 1024 * 1024
MAX_PIXELS = 16_000_000
MAX_BLOCKS = 5000
MAX_WORDS = 200_000
MAX_TEXT_CHARS = 1_000_000
MAX_OUTPUT_BYTES = 16 * 1024 * 1024
MAX_STDERR_BYTES = 64 * 1024
TIMEOUT_SECONDS = 60
OCR_COMMAND = ("tesseract", "stdin", "stdout", "-l", "chi_sim+eng", "--psm", "3", "tsv")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
TSV_HEADER = "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext"
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
PngImagePlugin.MAX_TEXT_CHUNK = 256 * 1024
PngImagePlugin.MAX_TEXT_MEMORY = 1024 * 1024
warnings.filterwarnings("error", category=Image.DecompressionBombWarning)


class OcrError(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code, self.status = code, status


def run_bounded(command, data=b"", *, timeout=TIMEOUT_SECONDS,
                output_limit=MAX_OUTPUT_BYTES, stderr_limit=MAX_STDERR_BYTES):
    """Drain pipes concurrently; never buffer more than each fixed output limit."""
    if timeout <= 0:
        raise OcrError("ocr_timeout", "OCR processing exceeded 60 seconds", 504)
    try:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, shell=False)
    except OSError:
        raise OcrError("engine_unavailable", "Tesseract could not be started", 503) from None
    output, errors = bytearray(), bytearray()
    overflow = threading.Event()

    def kill():
        try:
            process.kill()
        except OSError:
            pass

    def drain(stream, target, limit):
        try:
            while chunk := stream.read(64 * 1024):
                if len(target) + len(chunk) > limit:
                    overflow.set()
                    kill()
                    return
                target.extend(chunk)
        finally:
            stream.close()

    def write():
        try:
            process.stdin.write(data)
            process.stdin.flush()
        except (BrokenPipeError, OSError):
            pass
        finally:
            process.stdin.close()

    readers = [threading.Thread(target=drain, args=(process.stdout, output, output_limit), daemon=True),
               threading.Thread(target=drain, args=(process.stderr, errors, stderr_limit), daemon=True)]
    writer = threading.Thread(target=write, daemon=True)
    for thread in readers + [writer]:
        thread.start()
    timed_out = False
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        kill()
        process.wait(timeout=3)
    finally:
        for thread in readers + [writer]:
            thread.join(timeout=3)
    if timed_out:
        raise OcrError("ocr_timeout", "OCR processing exceeded 60 seconds", 504)
    if overflow.is_set():
        raise OcrError("output_too_large", "OCR engine output exceeded its limit", 502)
    if process.returncode != 0:
        # Neither document text nor arbitrary engine stderr is exposed in diagnostics.
        raise OcrError("ocr_failed", "Tesseract failed to recognize the image", 502)
    return bytes(output)


def health():
    result = {"ok": False, "ready": False, "engine": "tesseract", "version": None, "languages": []}
    try:
        version = run_bounded(("tesseract", "--version"), timeout=3, output_limit=32 * 1024).decode("utf-8")
        match = re.search(r"^tesseract\s+([^\s]+)", version)
        languages = run_bounded(("tesseract", "--list-langs"), timeout=3, output_limit=32 * 1024).decode("utf-8")
        result["version"] = match.group(1) if match else None
        result["languages"] = [line.strip() for line in languages.splitlines()
                               if re.fullmatch(r"[a-z][a-z0-9_]+", line.strip())]
        result["ready"] = bool(result["version"] and {"chi_sim", "eng"} <= set(result["languages"]))
        result["ok"] = result["ready"]
    except (OcrError, UnicodeError):
        pass
    return result


def normalize_png(raw):
    if not raw:
        raise OcrError("invalid_image", "PNG request body is empty")
    if len(raw) > MAX_INPUT_BYTES:
        raise OcrError("image_too_large", "PNG body must be between 1 byte and 12 MiB", 413)
    if not raw.startswith(PNG_SIGNATURE):
        raise OcrError("invalid_image", "Expected a valid PNG image")
    try:
        with Image.open(BytesIO(raw), formats=["PNG"]) as image:
            width, height = image.size
            if width <= 0 or height <= 0 or width * height > MAX_PIXELS:
                raise OcrError("image_too_large", "PNG dimensions must not exceed 16 million pixels", 413)
            if getattr(image, "n_frames", 1) != 1:
                raise OcrError("invalid_image", "Animated or multi-frame PNG images are not accepted")
            image.verify()
        with Image.open(BytesIO(raw), formats=["PNG"]) as image:
            image.load()
            # Flatten alpha onto paper white and omit all client-controlled metadata.
            rgba = image.convert("RGBA")
            paper = Image.new("RGB", rgba.size, "white")
            paper.paste(rgba, mask=rgba.getchannel("A"))
            buffer = BytesIO()
            paper.save(buffer, format="PNG", dpi=(300, 300))
            return buffer.getvalue(), width, height
    except (Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise OcrError("image_too_large", "PNG dimensions must not exceed 16 million pixels", 413) from None
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError):
        raise OcrError("invalid_image", "PNG is corrupt, truncated, or has invalid metadata") from None


def _cjk(char):
    return "\u2e80" <= char <= "\u9fff" or "\uf900" <= char <= "\ufaff" or "\U00020000" <= char <= "\U0003134f"


def join_words(words):
    result = ""
    for word in words:
        text = word["text"]
        if not result:
            result = text
            continue
        adjacent = ((_cjk(result[-1]) or result[-1] in "，。！？；：、）》】」』…") and _cjk(text[0])) or text[0] in "，。！？；：、）》】」』…,.!?;:%)]}" or result[-1] in "（《【「『([{"
        result += ("" if adjacent else " ") + text
    return result


def parse_tsv(raw, width, height):
    try:
        lines = raw.decode("utf-8").splitlines()
    except UnicodeError:
        raise OcrError("invalid_output", "Tesseract returned invalid UTF-8", 502) from None
    if not lines or lines[0].lstrip("\ufeff").rstrip("\r") != TSV_HEADER:
        raise OcrError("invalid_output", "Tesseract returned an invalid TSV header", 502)
    paragraphs = OrderedDict()
    word_count = text_chars = 0
    for line in lines[1:]:
        if not line.strip():
            continue
        fields = line.split("\t", 11)
        if len(fields) != 12:
            raise OcrError("invalid_output", "Tesseract returned an invalid TSV row", 502)
        try:
            level, page, block, paragraph, row, number, left, top, box_width, box_height = map(int, fields[:10])
            confidence = float(fields[10])
        except ValueError:
            raise OcrError("invalid_output", "Tesseract returned an invalid TSV value", 502) from None
        if level != 5:
            continue
        text = " ".join(fields[11].split())
        if not text:
            continue
        if (page != 1 or min(block, paragraph, row, number) < 1 or min(left, top) < 0 or
                min(box_width, box_height) <= 0 or left + box_width > width or top + box_height > height or
                not math.isfinite(confidence)):
            raise OcrError("invalid_output", "Tesseract returned invalid word coordinates or confidence", 502)
        word_count += 1
        text_chars += len(text)
        if word_count > MAX_WORDS or text_chars > MAX_TEXT_CHARS:
            raise OcrError("output_too_large", "OCR text exceeded its limit", 502)
        key = (page, block, paragraph)
        if key not in paragraphs:
            if len(paragraphs) >= MAX_BLOCKS:
                raise OcrError("too_many_blocks", "OCR output exceeded 5000 paragraph blocks", 502)
            paragraphs[key] = OrderedDict()
        paragraphs[key].setdefault(row, []).append({"text": text, "rect": [left, top, left + box_width, top + box_height],
                                                  "confidence": max(0, min(100, confidence)) / 100})
    result = []
    # Never sort globally by y/x: that interleaves neighboring columns.
    for (page, block, paragraph), rows in paragraphs.items():
        words = [word for row_words in rows.values() for word in row_words]
        weight = sum(len(word["text"].replace(" ", "")) for word in words)
        confidence = sum(word["confidence"] * len(word["text"].replace(" ", "")) for word in words) / weight
        result.append({"id": f"p{page}-b{block}-p{paragraph}", "type": "para",
                       "text": "\n".join(join_words(row_words) for row_words in rows.values()),
                       "rect": [min(w["rect"][0] for w in words), min(w["rect"][1] for w in words),
                                max(w["rect"][2] for w in words), max(w["rect"][3] for w in words)],
                       "confidence": round(confidence, 6), "words": len(words)})
    return result


def recognize(raw, runtime, timeout=TIMEOUT_SECONDS):
    deadline = time.monotonic() + min(TIMEOUT_SECONDS, timeout)
    png, width, height = normalize_png(raw)
    tsv = run_bounded(OCR_COMMAND, png, timeout=deadline - time.monotonic())
    blocks = parse_tsv(tsv, width, height)
    if time.monotonic() > deadline:
        raise OcrError("ocr_timeout", "OCR processing exceeded 60 seconds", 504)
    notes = ["ocr_text_only: formulas and table semantics are not reconstructed"]
    if not blocks:
        notes.append("no_text_detected")
    return {"engine": "tesseract", "version": runtime["version"], "width": width, "height": height,
            "blocks": blocks, "warnings": notes}
