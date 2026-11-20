from http.client import HTTPConnection
from io import BytesIO
import json
import threading
import time
import unittest

from PIL import Image

from engine import MAX_INPUT_BYTES, MODEL_PROFILE, Line, PageError, assemble_page, read_png, table_cells
from server import Runtime, StructureServer, pack


def page_png():
    result = BytesIO()
    Image.new("RGBA", (100, 120), (10, 20, 30, 0)).save(result, format="PNG")
    return result.getvalue()


def payload(lines, regions, formulae=()):
    return {
        "overall_ocr_res": {
            "rec_texts": [line.text for line in lines],
            "rec_boxes": [line.rect for line in lines],
            "rec_scores": [line.confidence for line in lines],
        },
        "parsing_res_list": [
            {"block_label": label, "block_bbox": rect, "block_content": text} for label, rect, text in regions
        ],
        "formula_res_list": formulae,
    }


class StructureTests(unittest.TestCase):
    def test_recovers_raw_lines_within_their_column(self):
        # The global OCR order is row-first; the layout order is column-first.
        lines = [
            Line("左标题", (5, 10, 30, 15), .94), Line("右标题", (55, 10, 80, 15), .95),
            Line("左遗漏", (5, 20, 30, 25), .96), Line("右正文", (55, 20, 80, 25), .97),
            Line("左正文", (5, 30, 30, 35), .98),
        ]
        regions = [
            ("paragraph_title", (4, 9, 32, 16), "左标题"),
            ("text", (4, 29, 32, 36), "左正文"),
            ("paragraph_title", (54, 9, 82, 16), "右标题"),
            ("text", (54, 19, 82, 26), "右正文"),
        ]
        result = assemble_page(payload(lines, regions), 100, 120, lambda *_: None)
        self.assertEqual(MODEL_PROFILE, result["profile"])
        self.assertEqual(["左标题", "左遗漏", "左正文", "右标题", "右正文"],
                         [block["text"] for block in result["blocks"]])
        self.assertEqual(lines[2].rect, result["blocks"][1]["rect"])
        self.assertEqual(["pp_ocr_lines_recovered"], result["warnings"])
        self.assertEqual(2, result["blocks"][0]["level"])

    def test_formula_candidate_excludes_raw_math_text(self):
        line = Line("log misread", (5, 5, 30, 20), .94)
        region = ("formula", (3, 3, 32, 22), "$x=1$")
        formula = {"dt_polys": [3, 3, 32, 22], "rec_formula": r"x=\sqrt{4}"}
        result = assemble_page(payload([line], [region], [formula]), 100, 120, lambda *_: None)
        self.assertEqual({"id": "b1", "type": "formula", "text": "", "rect": region[1], "words": 0,
                          "latex": formula["rec_formula"]}, result["blocks"][0])
        self.assertEqual(["pp_formula_needs_review"], result["warnings"])

    def test_figures_without_ocr_preserve_the_source_crop(self):
        for label in ("image", "chart", "figure"):
            with self.subTest(label=label):
                region = (label, (8, 12, 92, 108), "![model image](untrusted-output.png)")
                result = assemble_page(payload([], [region]), 100, 120, lambda *_: None)
                self.assertEqual([{"id": "b1", "type": "figure", "text": "",
                                   "rect": region[1], "words": 0}], result["blocks"])
                self.assertEqual([], result["warnings"])

    def test_figures_preserve_each_ocr_line_once_in_its_own_location(self):
        for label in ("image", "chart", "figure"):
            with self.subTest(label=label):
                lines = [Line("步骤一", (12, 20, 40, 28), .94),
                         Line("步骤二", (55, 58, 85, 66), .97),
                         Line("图注", (10, 100, 30, 108), .98)]
                regions = [
                    (label, (8, 12, 92, 90), "步骤一\n步骤二"),
                    # The smaller overlapping text region owns this label; the figure must not repeat it.
                    ("text", (54, 57, 86, 67), "步骤二"),
                    ("text", (9, 99, 32, 109), "图注"),
                ]
                result = assemble_page(payload(lines, regions), 100, 120, lambda *_: None)
                figures = [block for block in result["blocks"] if block["type"] == "figure"]
                texts = [block for block in result["blocks"] if block["type"] == "para"]
                self.assertEqual(1, len(figures))
                self.assertEqual(regions[0][1], figures[0]["rect"])
                self.assertEqual("", figures[0]["text"])
                self.assertEqual([line.text for line in lines], [block["text"] for block in texts])
                self.assertEqual([line.rect for line in lines], [block["rect"] for block in texts])
                for line, block in zip(lines, texts):
                    self.assertAlmostEqual(line.confidence, block["confidence"])
                self.assertEqual(["b1", "b2", "b3", "b4"], [block["id"] for block in result["blocks"]])
                self.assertEqual([], result["warnings"])

    def test_table_preserves_positions_and_falls_back_for_spans_or_uncertainty(self):
        lines = [Line("17.35", (15, 16, 25, 19), .92), Line("98.2%", (34, 16, 43, 19), .97)]
        recognized = {"structure": ["<tr><td></td><td></td></tr>"],
                      "bbox": [[3, 4, 19, 4, 19, 12, 3, 12], [22, 4, 35, 12]],
                      "structure_score": .95}
        crop = (10, 10, 50, 40)
        region = ("table", (11, 11, 48, 39), "")
        result = assemble_page(payload(lines, [region]), 100, 120,
                               lambda _box, part: table_cells(recognized, crop, part))
        self.assertEqual([["17.35", "98.2%"]], result["blocks"][0]["rows"])
        self.assertEqual(region[1], result["blocks"][0]["rect"])
        self.assertEqual(.92, result["blocks"][0]["confidence"])
        recognized["structure"] = ['<tr><td colspan="2"></td></tr>']
        result = assemble_page(payload(lines, [region]), 100, 120,
                               lambda _box, part: table_cells(recognized, crop, part))
        self.assertEqual(["pp_table_uncertain"], result["warnings"])
        self.assertEqual(["17.35", "98.2%"], [block["text"] for block in result["blocks"]])

    def test_png_validation_preserves_raster_and_paper_background(self):
        image = read_png(page_png())
        self.assertEqual((100, 120), image.size)
        self.assertEqual((255, 255, 255), image.getpixel((0, 0)))
        with self.assertRaises(PageError) as malformed:
            read_png(b"JPEG is not PNG")
        self.assertEqual("invalid_image", malformed.exception.code)


class FakeRuntime:
    def available(self):
        return True

    def recognize(self, body, _deadline):
        width, height = read_png(body).size
        return 200, pack(200, {"width": width, "height": height})[2:]


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.server = StructureServer(("127.0.0.1", 0), FakeRuntime())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, method, path, body=None, headers=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        try:
            connection.request(method, path, body, headers or {})
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_ready_png_only_and_bounded_body(self):
        health = self.request("GET", "/health")[1]
        self.assertTrue(health["ready"])
        self.assertEqual(MODEL_PROFILE, health["profile"])
        self.assertEqual(["PP-DocLayout_plus-L", "PP-OCRv5_server_det", "PP-OCRv5_server_rec",
                          "PP-FormulaNet_plus-L", "SLANet_plus"], health["models"])
        self.assertIn(health["device"], {"cpu", "gpu:0"})
        png = page_png()
        self.assertEqual((200, {"width": 100, "height": 120}),
                         self.request("POST", "/ocr", png, {"Content-Type": "image/png"}))
        self.assertEqual(415, self.request("POST", "/ocr", png, {"Content-Type": "application/json"})[0])
        self.assertEqual(413, self.request("POST", "/ocr", b"", {"Content-Length": str(MAX_INPUT_BYTES + 1)})[0])
        self.assertEqual(400, self.request("POST", "/ocr?path=/private", png)[0])
        self.assertEqual(403, self.request("POST", "/ocr", png, {"Content-Type": "image/png", "Authorization": "test"})[0])

    def test_busy_page_slot_rejects_second_request(self):
        self.assertTrue(self.server.page_slot.acquire(blocking=False))
        try:
            self.assertEqual(429, self.request("POST", "/ocr", page_png(), {"Content-Type": "image/png"})[0])
            self.assertEqual(200, self.request("GET", "/health")[0])
        finally:
            self.server.page_slot.release()


def stalled_worker(pipe):
    pipe.send_bytes(b"ready")
    pipe.recv_bytes()
    time.sleep(60)


def ready_worker(pipe):
    pipe.send_bytes(b"ready")
    try:
        pipe.recv_bytes()
    except EOFError:
        pass


def failed_worker(pipe):
    pipe.close()


class DeadlineTests(unittest.TestCase):
    def test_idle_crash_recovers_after_backoff_without_replaying_a_page(self):
        runtime = Runtime(ready_worker)
        try:
            end = time.monotonic() + 6
            while not runtime.available() and time.monotonic() < end:
                time.sleep(.03)
            self.assertTrue(runtime.available())
            original = runtime.process
            original.kill()
            original.join(timeout=1)
            self.assertFalse(runtime.available())
            self.assertEqual(original.pid, runtime.process.pid)
            self.assertIsNotNone(runtime.restart_after)
            self.assertFalse(runtime.available())
            self.assertEqual(original.pid, runtime.process.pid)
            runtime.restart_after = time.monotonic() - 1
            self.assertFalse(runtime.available())
            end = time.monotonic() + 6
            while not runtime.available() and time.monotonic() < end:
                time.sleep(.03)
            self.assertTrue(runtime.available())
            self.assertNotEqual(original.pid, runtime.process.pid)
            self.assertEqual(5, runtime.restart_delay)
        finally:
            runtime.close()
        self.assertFalse(runtime.available())

    def test_repeated_initialization_failure_increases_bounded_backoff(self):
        runtime = Runtime(failed_worker)
        try:
            for expected_delay in (10, 20, 40, 60, 60):
                runtime.process.join(timeout=3)
                self.assertFalse(runtime.available())
                self.assertIsNotNone(runtime.restart_after)
                self.assertEqual(expected_delay, runtime.restart_delay)
                original = runtime.process
                self.assertFalse(runtime.available())
                self.assertEqual(original.pid, runtime.process.pid)
                runtime.restart_after = time.monotonic() - 1
                self.assertFalse(runtime.available())
                self.assertNotEqual(original.pid, runtime.process.pid)
        finally:
            runtime.close()

    def test_timed_out_worker_is_replaced(self):
        runtime = Runtime(stalled_worker)
        try:
            end = time.monotonic() + 6
            while not runtime.available() and time.monotonic() < end:
                time.sleep(.03)
            self.assertTrue(runtime.available())
            process = runtime.process
            with self.assertRaises(PageError) as raised:
                runtime.recognize(b"synthetic", time.monotonic() + .08)
            self.assertEqual(504, raised.exception.status)
            self.assertFalse(process.is_alive())
            self.assertNotEqual(process.pid, runtime.process.pid)
        finally:
            runtime.close()


if __name__ == "__main__":
    unittest.main()
