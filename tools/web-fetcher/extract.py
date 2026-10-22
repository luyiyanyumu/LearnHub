"""DOM conversion with literal math/code protection and absolute links."""
from __future__ import annotations

import re
import uuid
from urllib.parse import urljoin, urlsplit


def extract_markdown(html: str, url: str, max_chars: int) -> tuple[str, str, bool]:
    # Optional dependencies are imported only for actual rendered-page extraction.
    from lxml import etree
    from lxml import html as dom
    from markdownify import markdownify

    tree = dom.fromstring(html or "<html><body></body></html>",
                          parser=dom.HTMLParser(no_network=True, huge_tree=False))
    titles = tree.xpath("//title")
    title = titles[0].text_content().strip()[:1000] if titles else ""
    bases = tree.xpath("//base[@href]")
    link_base = urljoin(url, bases[0].get("href")) if bases else url
    if urlsplit(link_base).scheme not in {"http", "https"}:
        link_base = url
    candidates = tree.xpath("//main|//article")
    root = next((node for node in candidates if len(node.text_content().strip()) > 80), None)
    if root is None:
        bodies = tree.xpath("//body")
        root = bodies[0] if bodies else tree
    protected: dict[str, str] = {}
    prefix = "ZZLH" + uuid.uuid4().hex.upper()

    def token(value: str) -> str:
        key = prefix + str(len(protected)) + "ZZ"
        protected[key] = value
        return key

    def replace(node, value: str):
        marker = dom.Element("span")
        marker.text, marker.tail = token(value), node.tail
        parent = node.getparent()
        if parent is not None:
            parent.replace(node, marker)

    # Capture TeX before dropping scripts or math-renderer noise.
    for node in list(root.xpath(".//script[contains(@type, 'math/tex')]|.//math|.//*[contains(concat(' ', @class, ' '), ' katex ')]")):
        if node.getparent() is None:
            continue
        annotations = node.xpath(".//annotation[@encoding='application/x-tex']")
        if annotations:
            tex = annotations[0].text_content()
            display = (node.get("display") == "block"
                       or bool(node.xpath(".//math[@display='block']|ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' katex-display ')]")))
            replace(node, "\n\n$$\n" + tex + "\n$$\n\n" if display else "$" + tex + "$")
        elif node.tag == "script":
            tex = node.text or ""
            replace(node, "\n\n$$\n" + tex + "\n$$\n\n" if "mode=display" in node.get("type", "") else "$" + tex + "$")
        elif node.tag == "math":
            replace(node, etree.tostring(node, encoding="unicode", with_tail=False))
    for node in list(root.xpath(".//script|.//style|.//noscript|.//template|.//svg|.//iframe|.//nav|.//footer|.//header|.//aside")):
        node.drop_tree()
    for node in root.xpath(".//*[@href or @src]"):
        for attribute in ("href", "src"):
            value = node.get(attribute)
            if value is not None:
                absolute = urljoin(link_base, value)
                if urlsplit(absolute).scheme in {"http", "https", "mailto"}:
                    node.set(attribute, absolute)
                else:
                    node.attrib.pop(attribute, None)
    for node in list(root.xpath(".//pre")):
        code = node.text_content()
        language = ""
        for item in [node] + node.xpath(".//code"):
            match = re.search(r"(?:language|lang)-([\w+#-]+)", item.get("class", ""))
            if match:
                language = match.group(1)
                break
        fence = "`" * max(3, max((len(run) + 1 for run in re.findall(r"`+", code)), default=0))
        replace(node, "\n\n" + fence + language + "\n" + code + ("" if code.endswith("\n") else "\n") + fence + "\n\n")
    for node in list(root.xpath(".//code")):
        code = node.text_content()
        fence = "`" * max(1, max((len(run) + 1 for run in re.findall(r"`+", code)), default=0))
        replace(node, fence + " " + code + " " + fence)
    # markdownify escapes underscores/backslashes; a text node containing TeX must stay literal.
    for node in root.iter():
        for attribute in ("text", "tail"):
            value = getattr(node, attribute)
            if value and ("$" in value or "\\(" in value or "\\[" in value or "\\begin{" in value):
                setattr(node, attribute, token(value))
    markdown = markdownify(etree.tostring(root, encoding="unicode"), heading_style="ATX", bullets="-")
    pattern = "(" + re.escape(prefix) + r"\d+ZZ)"
    output, length, truncated = [], 0, False
    for piece in re.split(pattern, markdown):
        value = protected.get(piece, piece)
        if length + len(value) > max_chars:
            if piece not in protected:
                output.append(value[:max_chars - length])
            truncated = True
            break
        output.append(value)
        length += len(value)
    return title, "".join(output).strip(), truncated
