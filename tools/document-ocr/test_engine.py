from io import BytesIO
import struct
import sys
import time
import unittest
from unittest.mock import patch
import zlib

from PIL import Image

import engine
from engine import OcrError, TSV_HEADER, join_words, normalize_png, parse_tsv, recognize, run_bounded


def png(width=160, height=100, mode="RGB"):
    buffer = BytesIO()
    Image.new(mode, (width, height), (0, 0, 0, 0) if mode == "RGBA" else "white").save(buffer, format="PNG")
    return buffer.getvalue()


def word(text, *, block=1, paragraph=1, line=1, number=1, x=10, y=10, width=20, height=10, conf=90):
    return f"5\t1\t{block}\t{paragraph}\t{line}\t{number}\t{x}\t{y}\t{width}\t{height}\t{conf}\t{text}"


def tsv(*rows):
    return (TSV_HEADER + "\n" + "\n".join(rows) + "\n").encode()


class EngineTests(unittest.TestCase):
    def assert_code(self, code, callback, status=None):
        with self.assertRaises(OcrError) as caught:
            callback()
        self.assertEqual(caught.exception.code, code)
        if status:
            self.assertEqual(caught.exception.status, status)

    def test_chinese_words_punctuation_and_english_spacing_preserve_lines(self):
        blocks = parse_tsv(tsv(word("中"), word("文", number=2, x=30), word("，", number=3, x=50),
                               word("阅读", number=4, x=70), word("LearnHub", line=2, y=35),
                               word("OCR", line=2, number=2, x=40, y=35)), 160, 100)
        self.assertEqual(blocks[0]["text"], "中文，阅读\nLearnHub OCR")
        self.assertEqual(blocks[0]["words"], 6)

    def test_health_requires_both_language_packs_and_reports_real_version(self):
        with patch.object(engine, "run_bounded", side_effect=[b"tesseract 5.3.0\n leptonica-1.82.0\n", b"List of available languages (3):\nchi_sim\neng\nosd\n"]):
            runtime = engine.health()
        self.assertTrue(runtime["ready"])
        self.assertEqual(runtime["version"], "5.3.0")
        self.assertEqual(runtime["languages"], ["chi_sim", "eng", "osd"])
        with patch.object(engine, "run_bounded", side_effect=[b"tesseract 5.3.0\n", b"List of available languages (1):\neng\n"]):
            self.assertFalse(engine.health()["ready"])

    def test_unavailable_engine_health_does_not_claim_readiness(self):
        with patch.object(engine, "run_bounded", side_effect=OcrError("engine_unavailable", "missing", 503)):
            runtime = engine.health()
        self.assertFalse(runtime["ready"])
        self.assertFalse(runtime["ok"])

    def test_columns_and_paragraphs_follow_tsv_order_instead_of_y_sort(self):
        blocks = parse_tsv(tsv(word("Left-top", x=10, y=10), word("Left-bottom", line=2, y=75),
                               word("Left-next-paragraph", paragraph=2, y=90),
                               word("Right-top", block=2, x=95, y=10)), 160, 110)
        self.assertEqual([b["id"] for b in blocks], ["p1-b1-p1", "p1-b1-p2", "p1-b2-p1"])
        self.assertEqual([b["text"] for b in blocks], ["Left-top\nLeft-bottom", "Left-next-paragraph", "Right-top"])

    def test_coordinates_are_union_and_confidence_is_character_weighted(self):
        block = parse_tsv(tsv(word("中", x=10, y=12, width=10, height=12, conf=50),
                              word("abcd", number=2, x=50, y=30, width=42, height=20, conf=100)), 160, 100)[0]
        self.assertEqual(block["rect"], [10, 12, 92, 50])
        self.assertEqual(block["confidence"], .9)
        self.assertEqual(block["type"], "para")

    def test_non_word_tsv_hierarchy_does_not_become_text(self):
        self.assertEqual(parse_tsv(tsv("1\t1\t0\t0\t0\t0\t0\t0\t160\t100\t-1\t"), 160, 100), [])

    def test_invalid_confidence_or_coordinate_is_not_emitted(self):
        for row in (word("x", conf="nan"), word("x", x=-1), word("x", x=150, width=20)):
            with self.subTest(row=row):
                self.assert_code("invalid_output", lambda: parse_tsv(tsv(row), 160, 100), 502)

    def test_confidence_is_clamped_and_whitespace_does_not_inflate_weight(self):
        block = parse_tsv(tsv(word("a", conf=-1), word("b c", number=2, conf=110)), 160, 100)[0]
        self.assertAlmostEqual(block["confidence"], 2 / 3, places=6)

    def test_malformed_engine_output_is_explicit(self):
        for value in (b"", b"oops", tsv("5\t1"), tsv(word("x", conf="bad")), b"\xff"):
            self.assert_code("invalid_output", lambda: parse_tsv(value, 160, 100), 502)

    def test_5000_block_limit_is_not_silent_truncation(self):
        data = tsv(*(word("x", block=i) for i in range(1, 5002)))
        self.assert_code("too_many_blocks", lambda: parse_tsv(data, 160, 100), 502)

    def test_bad_images_jpeg_and_truncated_png_are_rejected(self):
        jpeg = BytesIO()
        Image.new("RGB", (10, 10)).save(jpeg, format="JPEG")
        for raw in (b"", b"not a picture", jpeg.getvalue(), png()[:40], b"\x89PNG\r\n\x1a\n"):
            self.assert_code("invalid_image", lambda: normalize_png(raw), 400)

    def test_oversized_body_and_dimensions_rejected_before_pixel_allocation(self):
        self.assert_code("image_too_large", lambda: normalize_png(b"x" * (engine.MAX_INPUT_BYTES + 1)), 413)
        def chunk(kind, data):
            return struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data))
        oversized = engine.PNG_SIGNATURE + chunk(b"IHDR", struct.pack("!IIBBBBB", 4001, 4000, 8, 2, 0, 0, 0)) + chunk(b"IEND", b"")
        self.assert_code("image_too_large", lambda: normalize_png(oversized), 413)

    def test_transparent_png_flattens_to_white_without_client_metadata(self):
        normalized, width, height = normalize_png(png(mode="RGBA"))
        with Image.open(BytesIO(normalized)) as image:
            self.assertEqual((width, height), (160, 100))
            self.assertEqual(image.getpixel((10, 10)), (255, 255, 255))
            self.assertEqual(image.mode, "RGB")

    def test_animated_png_is_not_treated_as_a_single_pdf_page(self):
        raw = BytesIO()
        frames = [Image.new("RGB", (20, 20), color) for color in ("white", "black")]
        frames[0].save(raw, format="PNG", save_all=True, append_images=frames[1:], duration=100)
        self.assert_code("invalid_image", lambda: normalize_png(raw.getvalue()), 400)

    def test_fixed_command_receives_only_validated_png_and_no_user_arguments(self):
        with patch.object(engine, "run_bounded", return_value=tsv(word("hello"))) as run:
            result = recognize(png(), {"version": "synthetic"})
        self.assertEqual(run.call_args.args[0], engine.OCR_COMMAND)
        self.assertTrue(run.call_args.args[1].startswith(engine.PNG_SIGNATURE))
        self.assertEqual(result["width"], 160)
        self.assertEqual(result["blocks"][0]["text"], "hello")
        self.assertIn("ocr_text_only", result["warnings"][0])

    def test_no_text_is_successful_with_explicit_warning(self):
        with patch.object(engine, "run_bounded", return_value=tsv()):
            result = recognize(png(), {"version": "synthetic"})
        self.assertEqual(result["blocks"], [])
        self.assertIn("no_text_detected", result["warnings"])

    def test_real_child_process_timeout_is_bounded_and_stopped(self):
        start = time.monotonic()
        self.assert_code("ocr_timeout", lambda: run_bounded((sys.executable, "-c", "import time; time.sleep(15)"), timeout=.15), 504)
        self.assertLess(time.monotonic() - start, 4)

    def test_real_child_output_limit_is_enforced_without_unbounded_buffering(self):
        self.assert_code("output_too_large", lambda: run_bounded(
            (sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x'*2000000)"), output_limit=4096, timeout=5), 502)

    def test_engine_stderr_has_a_separate_hard_limit(self):
        self.assert_code("output_too_large", lambda: run_bounded(
            (sys.executable, "-c", "import sys; sys.stderr.buffer.write(b'x'*2000000)"), stderr_limit=4096, timeout=5), 502)

    def test_child_failure_does_not_return_document_text_from_stderr(self):
        with self.assertRaises(OcrError) as caught:
            run_bounded((sys.executable, "-c", "import sys; sys.stderr.write('synthetic private text'); sys.exit(1)"))
        self.assertNotIn("synthetic private", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
