"""Structured page inference with no coordinate-changing preprocessing."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from html.parser import HTMLParser
from io import BytesIO
import math
import os
from pathlib import Path
import unicodedata

from PIL import Image, PngImagePlugin

from models import FILES, MODELS

ENGINE = "pp-structurev3"
VERSION = "3.7.0-hq2"
MODEL_PROFILE = "high-quality-v2"
DEVICE = os.environ.get("LEARNHUB_STRUCTURE_DEVICE", "cpu")
MAX_INPUT_BYTES = 12 * 1024 * 1024
MAX_OUTPUT_BYTES = 2 * 1024 * 1024
MAX_PIXELS = 12_000_000
MAX_SIDE = 10_000
MAX_BLOCKS = 5000
MAX_TABLES = 8
MAX_CELL_CHARS = 4096
TIMEOUT_SECONDS = 240
HEADINGS = {"doc_title": 1, "title": 1, "paragraph_title": 2, "heading": 2}
FIGURES = {"image", "chart", "figure"}

Image.MAX_IMAGE_PIXELS = MAX_PIXELS
PngImagePlugin.MAX_TEXT_CHUNK = 256 * 1024
PngImagePlugin.MAX_TEXT_MEMORY = 1024 * 1024


class PageError(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(message)
        self.code, self.status = code, status


def read_png(raw):
    if len(raw) > MAX_INPUT_BYTES:
        raise PageError("image_too_large", "PNG body exceeds 12 MiB", 413)
    if not raw.startswith(b"\x89PNG\r\n\x1a\n"):
        raise PageError("invalid_image", "Expected a valid PNG image")
    try:
        with Image.open(BytesIO(raw), formats=["PNG"]) as image:
            width, height = image.size
            if width * height > MAX_PIXELS or max(width, height) > MAX_SIDE:
                raise PageError("image_too_large", "PNG dimensions exceed the page limit", 413)
            if getattr(image, "n_frames", 1) != 1:
                raise PageError("invalid_image", "Multi-frame PNG images are not accepted")
            image.verify()
        with Image.open(BytesIO(raw), formats=["PNG"]) as image:
            if "A" in image.getbands() or "transparency" in image.info:
                rgba = image.convert("RGBA")
                paper = Image.new("RGB", rgba.size, "white")
                paper.paste(rgba, mask=rgba.getchannel("A"))
                return paper
            return image.convert("RGB")
    except Image.DecompressionBombError:
        raise PageError("image_too_large", "PNG dimensions exceed the page limit", 413) from None
    except (OSError, ValueError, SyntaxError):
        raise PageError("invalid_image", "PNG is corrupt or truncated") from None


def clean_text(value):
    if not isinstance(value, str):
        return ""
    return "".join(char for char in value if char in "\n\t" or ord(char) >= 32 and ord(char) != 127).strip()


def normalized_text(value):
    return "".join(unicodedata.normalize("NFKC", value).split())


def rectangle(value, width, height):
    """Accept a four-number rectangle or a flattened/nested quadrilateral."""
    if not isinstance(value, (list, tuple)):
        return None
    if value and isinstance(value[0], (list, tuple)):
        if not all(isinstance(point, (list, tuple)) and len(point) == 2 for point in value):
            return None
        value = [number for point in value for number in point]
    if len(value) not in {4, 8} or not all(
        isinstance(number, (int, float)) and math.isfinite(number) for number in value
    ):
        return None
    x, y = value[0::2], value[1::2]
    box = (max(0, min(x)), max(0, min(y)), min(width, max(x)), min(height, max(y)))
    return box if area(box) else None


def area(box):
    return max(0, box[2] - box[0]) * max(0, box[3] - box[1])


def coverage(box, other):
    overlap = (max(box[0], other[0]), max(box[1], other[1]), min(box[2], other[2]), min(box[3], other[3]))
    return area(overlap) / area(box)


def union(boxes):
    return (
        min(box[0] for box in boxes), min(box[1] for box in boxes),
        max(box[2] for box in boxes), max(box[3] for box in boxes),
    )


def score(value):
    return value if isinstance(value, (int, float)) and 0 <= value <= 1 else None


@dataclass(frozen=True)
class Line:
    text: str
    rect: tuple
    confidence: float


@dataclass(frozen=True)
class Region:
    label: str
    rect: tuple
    text: str = ""


def page_lines(payload, width, height, warnings):
    ocr = payload.get("overall_ocr_res", {})
    texts = ocr.get("rec_texts", [])
    boxes = ocr.get("rec_boxes") or ocr.get("rec_polys", [])
    scores = ocr.get("rec_scores", [])
    if max(len(texts), len(boxes), len(scores)) > MAX_BLOCKS:
        raise PageError("output_too_large", "Page contains too many OCR lines", 502)
    if not len(texts) == len(boxes) == len(scores):
        warnings.add("pp_ocr_line_invalid")
    lines = []
    for text, box, confidence in zip(texts, boxes, scores):
        text, rect, confidence = clean_text(text), rectangle(box, width, height), score(confidence)
        if text and rect and confidence is not None:
            lines.append(Line(text, rect, confidence))
        elif text:
            warnings.add("pp_ocr_line_invalid")
    return lines


def page_regions(payload, width, height):
    regions = []
    for block in payload.get("parsing_res_list", []):
        rect = rectangle(block.get("block_bbox"), width, height)
        if rect:
            regions.append(Region(block["block_label"], rect, clean_text(block.get("block_content"))))
    if len(regions) > MAX_BLOCKS:
        raise PageError("output_too_large", "Page contains too many layout regions", 502)
    return regions


def line_order(line):
    return (line.rect[1], line.rect[0])


def orphan_position(box, regions):
    """Insert a missed line by its closest same-column region, respecting PP's column order."""
    if not regions:
        return 0, 0

    def distance(index):
        rect = regions[index].rect
        horizontal = min(box[2], rect[2]) - max(box[0], rect[0])
        gap = max(0, box[1] - rect[3], rect[1] - box[3])
        return horizontal < .25 * (box[2] - box[0]), gap, abs(box[0] - rect[0])

    anchor = min(range(len(regions)), key=distance)
    return anchor, -1 if box[1] < regions[anchor].rect[1] else 1


class GridParser(HTMLParser):
    """Read only SLANet cell topology. Never forward its markup."""
    def __init__(self):
        super().__init__()
        self.rows = []
        self.row = None
        self.ambiguous = False

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.ambiguous |= self.row is not None
            self.row = 0
        elif tag in {"td", "th"}:
            self.ambiguous |= self.row is None or any(
                key in {"rowspan", "colspan"} and value != "1" for key, value in attrs
            )
            if self.row is not None:
                self.row += 1

    def handle_endtag(self, tag):
        if tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None

    def grid(self):
        if self.ambiguous or self.row is not None or not self.rows or len(self.rows) > 64:
            return None
        columns = self.rows[0]
        return (len(self.rows), columns) if 0 < columns <= 48 and all(n == columns for n in self.rows) else None


def table_cells(result, crop, lines):
    parser = GridParser()
    parser.feed("".join(result.get("structure", [])))
    parser.close()
    grid = parser.grid()
    if not grid or not lines:
        return None
    rows, columns = grid
    local = [rectangle(box, crop[2] - crop[0], crop[3] - crop[1]) for box in result.get("bbox", [])]
    if len(local) != rows * columns or not all(local):
        return None
    cells = [(x1 + crop[0], y1 + crop[1], x2 + crop[0], y2 + crop[1]) for x1, y1, x2, y2 in local]
    text = [[] for _ in cells]
    for line in lines:
        options = sorted(((coverage(line.rect, cell), index) for index, cell in enumerate(cells)), reverse=True)
        best, index = options[0]
        if best < .45 or len(options) > 1 and best - options[1][0] < .10:
            return None
        text[index].append(line)
    values = [" ".join(line.text for line in sorted(cell, key=line_order)) for cell in text]
    if any(len(value) > MAX_CELL_CHARS for value in values):
        return None
    confidence = min(line.confidence for line in lines)
    structure_score = score(result.get("structure_score"))
    if structure_score is not None:
        confidence = min(confidence, structure_score)
    if confidence < .65:
        return None
    return [values[i:i+columns] for i in range(0, len(values), columns)], confidence


def text_block(lines, heading=None):
    text = "\n".join(line.text for line in lines)
    weights = [len(line.text) for line in lines]
    block = {
        "type": "heading" if heading else "para",
        "text": text,
        "rect": union([line.rect for line in lines]),
        "confidence": sum(line.confidence * weight for line, weight in zip(lines, weights)) / sum(weights),
        "words": len(text.split()),
    }
    if heading:
        block["level"] = heading
    return block


def assemble_page(payload, width, height, recognize_table):
    warnings = set()
    regions = page_regions(payload, width, height)
    lines = page_lines(payload, width, height, warnings)
    assigned, orphans = defaultdict(list), defaultdict(list)
    formula_indices = {index for index, region in enumerate(regions) if region.label == "formula"}
    table_indices = {index for index, region in enumerate(regions) if region.label == "table"}
    semantic = formula_indices | table_indices
    parsed_text = normalized_text("".join(region.text for region in regions if region.label not in {"formula", "table"}))
    recognized_text = normalized_text("".join(line.text for line in lines))
    for line in lines:
        # A formula's raw visual OCR belongs to its candidate, never the search body.
        matches = [(coverage(line.rect, region.rect), -area(region.rect), index)
                   for index, region in enumerate(regions)]
        formula = max((matches[index] for index in formula_indices), default=(0, 0, -1))
        table = max((matches[index] for index in table_indices), default=(0, 0, -1))
        ordinary = max((item for item in matches if item[2] not in semantic), default=(0, 0, -1))
        if formula[0] >= .50:
            assigned[formula[2]].append(line)
        elif table[0] >= .60:
            assigned[table[2]].append(line)
        elif ordinary[0] >= .55:
            assigned[ordinary[2]].append(line)
            if normalized_text(line.text) not in parsed_text:
                warnings.add("pp_ocr_lines_recovered")
        else:
            orphans[orphan_position(line.rect, regions)].append(line)
            warnings.add("pp_ocr_lines_recovered")

    formulae = [(rectangle(result.get("dt_polys"), width, height), clean_text(result.get("rec_formula")))
                for result in payload.get("formula_res_list", [])]
    blocks = []
    table_attempts = 0
    for index, region in enumerate(regions):
        blocks.extend(text_block([line]) for line in sorted(orphans.pop((index, -1), []), key=line_order))
        part = sorted(assigned[index], key=line_order)
        if region.label == "formula":
            candidates = [(coverage(box, region.rect), value) for box, value in formulae if box and value]
            overlap, latex = max(candidates, default=(0, ""))
            block = {"type": "formula", "text": "", "rect": region.rect, "words": 0}
            if overlap >= .60 and len(latex) <= MAX_CELL_CHARS:
                block["latex"] = latex
            blocks.append(block)
            warnings.add("pp_formula_needs_review")
        elif region.label == "table":
            table_attempts += 1
            table = recognize_table(region.rect, part) if part and table_attempts <= MAX_TABLES else None
            if table:
                rows, confidence = table
                blocks.append({"type": "table", "text": "", "rows": rows, "rect": region.rect, "confidence": confidence,
                               "words": sum(len(value.split()) for row in rows for value in row)})
            else:
                warnings.add("pp_table_uncertain")
                blocks.extend(text_block([line]) for line in part)
        elif region.label in FIGURES:
            # Preserve the source crop even when a diagram contains no readable text.
            # Assigned labels remain separate, located OCR evidence; never copy model markup.
            blocks.append({"type": "figure", "text": "", "rect": region.rect, "words": 0})
            blocks.extend(text_block([line]) for line in part)
        elif part:
            blocks.append(text_block(part, HEADINGS.get(region.label)))
        elif region.text and normalized_text(region.text) not in recognized_text:
            warnings.add("pp_layout_text_unconfirmed")
        blocks.extend(text_block([line]) for line in sorted(orphans.pop((index, 1), []), key=line_order))
    for part in orphans.values():
        blocks.extend(text_block([line]) for line in sorted(part, key=line_order))
    if len(blocks) > MAX_BLOCKS:
        raise PageError("output_too_large", "Page contains too many output blocks", 502)
    for index, block in enumerate(blocks, 1):
        block["id"] = f"b{index}"
    return {"engine": ENGINE, "version": VERSION, "profile": MODEL_PROFILE,
            "width": width, "height": height, "blocks": blocks,
            "warnings": sorted(warnings)}


class ModelEngine:
    def __init__(self, model_root=Path("/opt/models")):
        if DEVICE not in {"cpu", "gpu:0"}:
            raise RuntimeError("LEARNHUB_STRUCTURE_DEVICE must be cpu or gpu:0")
        for name in MODELS:
            if not all((model_root / name / file).is_file() for file in FILES):
                raise RuntimeError("Required local model files are absent")
        import numpy as np
        from paddleocr import PPStructureV3, TableStructureRecognition

        self.np = np
        inference = {"device": DEVICE, "cpu_threads": 4, "enable_mkldnn": False}
        self.page = PPStructureV3(
            **inference,
            layout_detection_model_name="PP-DocLayout_plus-L",
            layout_detection_model_dir=str(model_root / "PP-DocLayout_plus-L"),
            text_detection_model_name="PP-OCRv5_server_det",
            text_detection_model_dir=str(model_root / "PP-OCRv5_server_det"),
            # Bound detector activations without resizing the source raster or recognition crops.
            # Paddle rescales detected boxes back to source pixels before text recognition.
            text_det_limit_side_len=1920,
            text_det_limit_type="max",
            text_recognition_model_name="PP-OCRv5_server_rec",
            text_recognition_model_dir=str(model_root / "PP-OCRv5_server_rec"),
            text_recognition_batch_size=4,
            formula_recognition_model_name="PP-FormulaNet_plus-L",
            formula_recognition_model_dir=str(model_root / "PP-FormulaNet_plus-L"),
            formula_recognition_batch_size=1,
            use_region_detection=False,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
            use_seal_recognition=False,
            use_chart_recognition=False,
            use_table_recognition=False,
            use_formula_recognition=True,
        )
        self.table = TableStructureRecognition(**inference, model_name="SLANet_plus",
                                               model_dir=str(model_root / "SLANet_plus"))

    @staticmethod
    def predict(model, image):
        results = list(model.predict(input=image))
        if len(results) != 1:
            raise PageError("invalid_output", "Model did not return a single image result", 502)
        return results[0].json["res"]

    def recognize(self, raw):
        image = read_png(raw)
        width, height = image.size
        # PaddleX expects BGR ndarray. Raster dimensions and coordinates are unchanged.
        pixels = self.np.asarray(image)[:, :, ::-1].copy()
        page = self.predict(self.page, pixels)
        if (page.get("width"), page.get("height")) != (width, height):
            raise PageError("invalid_output", "Model page coordinates do not match the source image", 502)

        def recognize_table(box, lines):
            crop = (max(0, math.floor(box[0]) - 12), max(0, math.floor(box[1]) - 12),
                    min(width, math.ceil(box[2]) + 12), min(height, math.ceil(box[3]) + 12))
            try:
                result = self.predict(self.table, pixels[crop[1]:crop[3], crop[0]:crop[2]].copy())
                return table_cells(result, crop, lines)
            except Exception:
                # A model failure in one table should retain its located OCR text.
                return None

        return assemble_page(page, width, height, recognize_table)
