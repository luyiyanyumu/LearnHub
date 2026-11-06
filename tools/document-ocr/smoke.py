"""Real Tesseract and HTTP acceptance using only generated bilingual paper."""
from io import BytesIO
import json
import threading
import urllib.error
import urllib.request

from PIL import Image, ImageDraw, ImageFont

from engine import health
from server import OcrServer


def run():
    runtime = health()
    assert runtime["ready"], runtime
    image = Image.new("RGB", (2200, 1200), "white")
    draw = ImageDraw.Draw(image)
    font = ImageFont.truetype("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 60)
    # Deliberately clean, large synthetic text: this tests integration, not an accuracy benchmark.
    lines = ["中文学习资料合成阅读测试", "本地文字识别测试", "LearnHub synthetic document", "Offline OCR example 2026"]
    for index, text in enumerate(lines):
        draw.text((110, 110 + index * 190), text, fill="black", font=font)
    buffer = BytesIO()
    image.save(buffer, format="PNG", dpi=(300, 300))
    server = OcrServer(("127.0.0.1", 0), runtime_health=runtime)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{server.server_address[1]}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        request = urllib.request.Request(base + "/ocr", data=buffer.getvalue(), headers={"Content-Type": "image/png"})
        with opener.open(request, timeout=65) as response:
            result = json.load(response)
        text = "\n".join(block["text"] for block in result["blocks"])
        assert "中文学习资料合成阅读测试" in text, text
        assert "本地文字识别测试" in text, text
        assert "LearnHub synthetic document" in text, text
        assert "Offline OCR example 2026" in text, text
        assert (result["width"], result["height"]) == image.size
        for block in result["blocks"]:
            x0, y0, x1, y1 = block["rect"]
            assert 0 <= x0 < x1 <= image.width and 0 <= y0 < y1 <= image.height
            assert 0 <= block["confidence"] <= 1 and block["words"] > 0
        for path, data, content_type, expected in [
            ("/ocr?url=https://example.invalid", buffer.getvalue(), "image/png", 400),
            ("/ocr", b"not an image", "image/png", 400),
            ("/ocr", b'{}', "application/json", 415),
        ]:
            try:
                opener.open(urllib.request.Request(base + path, data=data, headers={"Content-Type": content_type}), timeout=5)
                raise AssertionError("Invalid request unexpectedly succeeded")
            except urllib.error.HTTPError as error:
                assert error.code == expected
        print(json.dumps({"ok": True, "runtime": runtime, "syntheticOnly": True, "chinese": True, "english": True,
                          "http": True, "coordinates": True, "blocks": len(result["blocks"]), "invalidInputsRejected": True}))
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


if __name__ == "__main__":
    run()
