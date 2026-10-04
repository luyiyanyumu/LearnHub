package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.converter.WordToHtmlConverter;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/** Original-file reading view, separate from the lossy retrieval text stored in file_info. */
@Service
@RequiredArgsConstructor
public class DocumentPreviewService {
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    private static final int MAX_HTML_CHARS = 12 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ALL_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Pattern CSS_RULE = Pattern.compile("\\.([a-zA-Z0-9_-]+)\\s*\\{([^}]+)}");
    private static final Set<String> HTML_TAGS = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "div", "span",
            "strong", "b", "em", "i", "u", "s", "sub", "sup", "br", "table", "tbody", "thead", "tr", "td", "th", "ul", "ol", "li", "a", "img");

    private final FileStorageService files;

    public Map<String, Object> preview(Long id) {
        var item = files.download(id);
        String name = item.originName();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        try {
            Path path = item.resource().getFile().toPath();
            Map<String, Object> result = new LinkedHashMap<>(render(path, ext));
            result.put("id", id);
            result.put("originName", name);
            result.put("ext", ext);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("读取原文失败，请下载原文件查看", e);
        }
    }

    /** Package visible for tests using newly generated, isolated documents. */
    Map<String, Object> render(Path path, String ext) throws IOException {
        if (!Set.of("md", "markdown", "docx", "docm", "doc").contains(ext)) {
            throw new IllegalArgumentException("原文预览支持 Word（docx/docm/doc）和 Markdown（md/markdown）");
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("原文件超过 20 MiB，请下载原文件阅读");
        }
        if (ext.equals("md") || ext.equals("markdown")) {
            try {
                String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
                if (source.startsWith("\uFEFF")) source = source.substring(1);
                return Map.of("format", "markdown", "source", source, "html", "", "warnings", java.util.List.of());
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IllegalArgumentException("Markdown 原文件不是有效 UTF-8，请另存为 UTF-8 后重新上传");
            }
        }
        Preview out = new Preview();
        out.warnings.add("Word 按原文件结构排版阅读；分页、分栏及浮动对象的位置可能与 Word 软件不同，精确版式请下载原文件查看。");
        try (InputStream in = Files.newInputStream(path)) {
            if (ext.equals("doc")) renderLegacy(in, out);
            else {
                validateZip(path);
                try (XWPFDocument doc = new XWPFDocument(in)) {
                    renderOoxml(doc, out);
                }
            }
        } catch (PreviewLimitException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Word 原文预览失败，文件可能损坏、加密或格式不匹配；请下载原文件查看", e);
        }
        return Map.of("format", "word", "html", out.html.toString(), "source", "", "warnings", java.util.List.copyOf(out.warnings));
    }

    private void renderOoxml(XWPFDocument doc, Preview out) {
        out.owner = doc;
        out.add("<div class=\"word-document\">");
        renderChildren(doc.getDocument().getBody().getDomNode(), doc, out, 0);
        if (!doc.getFootnotes().isEmpty()) {
            out.add("<div class=\"word-notes\"><h2>脚注</h2>");
            doc.getFootnotes().stream().filter(n -> n.getId().signum() > 0).forEach(n -> {
                out.add("<div id=\"word-footnote-" + n.getId() + "\"><sup>[" + n.getId() + "]</sup>");
                renderPart(n.getCTFtnEdn().getDomNode(), n.getPart(), doc, out);
                out.add("</div>");
            });
            out.add("</div>");
        }
        if (!doc.getEndnotes().isEmpty()) {
            out.add("<div class=\"word-notes\"><h2>尾注</h2>");
            doc.getEndnotes().stream().filter(n -> n.getId().signum() > 0).forEach(n -> {
                out.add("<div id=\"word-endnote-" + n.getId() + "\"><sup>[" + n.getId() + "]</sup>");
                renderPart(n.getCTFtnEdn().getDomNode(), n.getPart(), doc, out);
                out.add("</div>");
            });
            out.add("</div>");
        }
        if (doc.getComments() != null && doc.getComments().length > 0) {
            out.add("<div class=\"word-comments\"><h2>批注</h2>");
            for (var comment : doc.getComments()) {
                out.add("<div id=\"word-comment-" + escape(comment.getId()) + "\"><strong>批注 " + escape(comment.getId())
                        + " · " + escape(comment.getAuthor()) + "</strong>");
                renderPart(comment.getCtComment().getDomNode(), comment.getPart(), doc, out);
                out.add("</div>");
            }
            out.add("</div>");
        }
        if (!doc.getHeaderList().isEmpty() || !doc.getFooterList().isEmpty()) {
            out.warnings.add("页眉和页脚集中在文末展示，不重复插入每个阅读页面。");
            out.add("<div class=\"word-notes\"><h2>页眉与页脚</h2>");
            doc.getHeaderList().forEach(h -> renderPart(h._getHdrFtr().getDomNode(), h, doc, out));
            doc.getFooterList().forEach(f -> renderPart(f._getHdrFtr().getDomNode(), f, doc, out));
            out.add("</div>");
        }
        out.add("</div>");
    }

    private void renderPart(Node node, POIXMLDocumentPart part, XWPFDocument doc, Preview out) {
        POIXMLDocumentPart previous = out.owner;
        out.owner = part;
        try { renderChildren(node, doc, out, 0); }
        finally { out.owner = previous; }
    }

    private void renderChildren(Node node, XWPFDocument doc, Preview out, int depth) {
        if (depth > 100) throw new PreviewLimitException("Word 内容嵌套过深，请下载原文件阅读");
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) renderNode(child, doc, out, depth + 1);
    }

    private void renderNode(Node node, XWPFDocument doc, Preview out, int depth) {
        if (node.getNodeType() != Node.ELEMENT_NODE) return;
        String local = local(node);
        if ("http://schemas.openxmlformats.org/markup-compatibility/2006".equals(node.getNamespaceURI())) {
            if (local.equals("AlternateContent")) {
                Node fallback = child(node, "Fallback");
                renderChildren(fallback != null ? fallback : node, doc, out, depth);
            } else renderChildren(node, doc, out, depth);
            return;
        }
        if (M.equals(node.getNamespaceURI())) {
            if (local.equals("oMath") || local.equals("oMathPara")) {
                out.warnings.add("Word 公式已保留为可读数学结构；复杂矩阵、上下限和特殊符号的位置可能有差异，精确公式请下载原文件查看。");
                out.add("<span class=\"word-equation\">");
                renderMath(node, out, depth + 1);
                out.add("</span>");
            }
            return;
        }
        if (!W.equals(node.getNamespaceURI())) return;
        switch (local) {
            case "p" -> {
                Node props = child(node, "pPr");
                String style = attr(child(props, "pStyle"), "val");
                String level = attr(child(props, "outlineLvl"), "val");
                if (level.isBlank() && doc.getStyles() != null && doc.getStyles().getStyle(style) != null) {
                    Node styleNode = doc.getStyles().getStyle(style).getCTStyle().getDomNode();
                    Node stylePr = child(styleNode, "pPr");
                    level = attr(child(stylePr, "outlineLvl"), "val");
                    String styleName = attr(child(styleNode, "name"), "val");
                    if (!styleName.isEmpty()) style = styleName;
                }
                Matcher heading = Pattern.compile("(?i)(?:heading|标题)[ _-]?([1-6])").matcher(style);
                int h = level.matches("[0-5]") ? Integer.parseInt(level) + 1 : heading.find() ? Integer.parseInt(heading.group(1)) : 0;
                String tag = h > 0 ? "h" + h : "p";
                String alignment = attr(child(props, "jc"), "val");
                String align = switch (alignment) { case "center" -> "center"; case "right", "end" -> "right"; case "both", "distribute" -> "justify"; default -> "left"; };
                String left = attr(child(props, "ind"), "left");
                String indent = left.matches("[0-9]{1,5}") ? ";margin-left:" + Math.min(Integer.parseInt(left) / 20.0, 144) + "pt" : "";
                out.add("<" + tag + " style=\"text-align:" + align + ";white-space:pre-wrap" + indent + "\">");
                Node numPr = child(props, "numPr");
                if (numPr != null) out.add("<span class=\"word-list-marker\">" + escape(listMarker(doc, numPr, out)) + " </span>");
                renderChildren(node, doc, out, depth);
                out.add("</" + tag + ">");
            }
            case "tbl" -> {
                out.add("<table><tbody>");
                renderChildren(node, doc, out, depth);
                out.add("</tbody></table>");
            }
            case "tr" -> {
                out.add("<tr>"); renderChildren(node, doc, out, depth); out.add("</tr>");
            }
            case "tc" -> {
                Node props = child(node, "tcPr");
                String span = attr(child(props, "gridSpan"), "val");
                String colspan = span.matches("[1-9][0-9]{0,2}") ? " colspan=\"" + span + "\"" : "";
                if (child(props, "vMerge") != null) out.warnings.add("表格的纵向合并单元格按阅读顺序展开；内容已保留。");
                String color = attr(child(props, "shd"), "fill");
                String background = color.matches("(?i)[0-9a-f]{6}") ? " style=\"background-color:#" + color + "\"" : "";
                out.add("<td" + colspan + background + ">");
                renderChildren(node, doc, out, depth); out.add("</td>");
            }
            case "r" -> {
                Node props = child(node, "rPr");
                String style = runStyle(props);
                out.add("<span" + (style.isEmpty() ? "" : " style=\"" + style + "\"") + ">");
                if (on(props, "b")) out.add("<strong>");
                if (on(props, "i")) out.add("<em>");
                if (on(props, "strike") || on(props, "dstrike")) out.add("<s>");
                String vertical = attr(child(props, "vertAlign"), "val");
                String vertTag = vertical.equals("superscript") ? "sup" : vertical.equals("subscript") ? "sub" : "";
                if (!vertTag.isEmpty()) out.add("<" + vertTag + ">");
                renderChildren(node, doc, out, depth);
                if (!vertTag.isEmpty()) out.add("</" + vertTag + ">");
                if (on(props, "strike") || on(props, "dstrike")) out.add("</s>");
                if (on(props, "i")) out.add("</em>");
                if (on(props, "b")) out.add("</strong>");
                out.add("</span>");
            }
            case "t", "delText" -> out.add(escape(text(node)));
            case "tab", "ptab" -> out.add("\t");
            case "br", "cr" -> out.add("<br>");
            case "noBreakHyphen" -> out.add("‑");
            case "softHyphen" -> out.add("&shy;");
            case "hyperlink" -> {
                String id = attrNs(node, R, "id");
                var relationship = out.owner == null ? null : out.owner.getPackagePart().getRelationship(id);
                String url = relationship != null ? relationship.getTargetURI().toString() : "#" + attr(node, "anchor");
                String safe = safeLink(url);
                if (!safe.isEmpty()) out.add("<a href=\"" + escape(safe) + "\" rel=\"noopener noreferrer\">");
                renderChildren(node, doc, out, depth);
                if (!safe.isEmpty()) out.add("</a>");
            }
            case "footnoteReference", "endnoteReference", "commentReference" -> {
                String id = attr(node, "id");
                if (id.matches("[0-9]+")) {
                    String kind = local.replace("Reference", "");
                    out.add("<sup><a href=\"#word-" + kind + "-" + id + "\">[" + (kind.equals("comment") ? "批注 " : "") + id + "]</a></sup>");
                }
            }
            case "drawing", "pict" -> renderDrawing(node, doc, out);
            case "object" -> {
                out.warnings.add("文档包含嵌入对象（例如旧版公式或 OLE 对象），仅展示对象自带的预览图；请下载原文件查看完整对象。");
                if (descendant(node, "imagedata") != null) renderDrawing(node, doc, out);
            }
            case "altChunk" -> out.warnings.add("文档包含外部内容块，原文预览不会加载外部页面，请下载原文件查看该内容。");
            case "sym" -> {
                String code = attr(node, "char");
                if (code.matches("(?i)[0-9a-f]{4,6}")) {
                    int value = Integer.parseInt(code, 16);
                    if (Character.isValidCodePoint(value)) out.add(escape(new String(Character.toChars(value))));
                    out.warnings.add("文档中的专用字体符号按字符预览，缺少原字体时外观可能不同，请下载原文件核对。");
                }
            }
            case "del", "moveFrom" -> out.warnings.add("修订内容按最终版本阅读：已隐藏删除内容，保留新增内容。");
            case "ins", "moveTo" -> {
                out.warnings.add("修订内容按最终版本阅读：已隐藏删除内容，保留新增内容。");
                renderChildren(node, doc, out, depth);
            }
            case "pPr", "rPr", "tblPr", "tblGrid", "trPr", "tcPr", "sectPr", "instrText", "fldChar", "bookmarkEnd", "commentRangeStart", "commentRangeEnd" -> { }
            case "bookmarkStart" -> {
                String name = attr(node, "name");
                if (!name.isBlank() && name.matches("[\\p{L}\\p{N}_-]{1,120}")) out.add("<span id=\"" + escape(name) + "\"></span>");
            }
            default -> renderChildren(node, doc, out, depth);
        }
    }

    private String listMarker(XWPFDocument doc, Node numPr, Preview out) {
        String id = attr(child(numPr, "numId"), "val");
        String level = attr(child(numPr, "ilvl"), "val");
        if (!id.matches("[0-9]+") || !level.matches("[0-9]+") || doc.getNumbering() == null) return "•";
        var num = doc.getNumbering().getNum(new java.math.BigInteger(id));
        if (num == null || num.getCTNum().getAbstractNumId() == null) return "•";
        var abs = doc.getNumbering().getAbstractNum(num.getCTNum().getAbstractNumId().getVal());
        if (abs == null) return "•";
        for (var lvl : abs.getCTAbstractNum().getLvlList()) {
            if (!lvl.getIlvl().toString().equals(level)) continue;
            String format = lvl.getNumFmt() == null ? "bullet" : lvl.getNumFmt().getVal().toString();
            if (format.equals("bullet")) return "•";
            out.warnings.add("编号列表保留阅读顺序，复杂多级编号可能与原文件不同。");
            String key = id + ":" + level;
            int start = lvl.getStart() == null ? 1 : lvl.getStart().getVal().intValue();
            int count = out.listCounters.compute(key, (k, v) -> v == null ? start : v + 1);
            return count + ".";
        }
        return "•";
    }

    private String runStyle(Node props) {
        StringBuilder style = new StringBuilder();
        String color = attr(child(props, "color"), "val");
        if (color.matches("(?i)[0-9a-f]{6}")) style.append("color:#").append(color).append(';');
        String size = attr(child(props, "sz"), "val");
        if (size.matches("[0-9]{1,3}")) {
            double pt = Integer.parseInt(size) / 2.0;
            if (pt >= 5 && pt <= 96) style.append("font-size:").append(pt).append("pt;");
        }
        Node underline = child(props, "u");
        if (underline != null && !Set.of("none", "false", "0").contains(attr(underline, "val"))) style.append("text-decoration:underline;");
        String highlight = attr(child(props, "highlight"), "val").toLowerCase(Locale.ROOT);
        Map<String, String> colors = Map.ofEntries(Map.entry("yellow", "#ffff00"), Map.entry("green", "#00ff00"), Map.entry("cyan", "#00ffff"),
                Map.entry("magenta", "#ff00ff"), Map.entry("blue", "#0000ff"), Map.entry("red", "#ff0000"), Map.entry("darkyellow", "#808000"),
                Map.entry("lightgray", "#d3d3d3"), Map.entry("darkgray", "#808080"));
        if (colors.containsKey(highlight)) style.append("background-color:").append(colors.get(highlight)).append(';');
        return style.toString();
    }

    private void renderDrawing(Node node, XWPFDocument doc, Preview out) {
        Node blip = descendant(node, "blip");
        Node image = blip == null ? descendant(node, "imagedata") : blip;
        String id = image == null ? "" : attrNs(image, R, blip == null ? "id" : "embed");
        var relation = out.owner == null ? null : out.owner.getRelationById(id);
        XWPFPictureData picture = relation instanceof XWPFPictureData data ? data : null;
        if (picture == null) {
            out.warnings.add("外部链接图片、图表或绘图对象未加载；预览不会访问外部图片服务器，请下载原文件查看。");
            // Text boxes often live inside drawings; retain their visible paragraphs.
            Node box = descendant(node, "txbxContent");
            if (box != null) renderChildren(box, doc, out, 0);
            return;
        }
        String src = out.image(picture.getData(), picture.getPackagePart().getContentType());
        if (!src.isEmpty()) {
            Node description = descendant(node, "docPr");
            String alt = description instanceof Element e ? e.getAttribute("descr") : "文档插图";
            Node extent = descendant(node, "extent");
            String cx = extent instanceof Element e ? e.getAttribute("cx") : "";
            String width = cx.matches("[0-9]{1,12}") ? ";width:" + Math.min(Long.parseLong(cx) / 9525.0, 1600) + "px" : "";
            out.add("<img src=\"" + src + "\" alt=\"" + escape(alt) + "\" style=\"max-width:100%;height:auto" + width + "\">");
        }
    }

    private void renderMath(Node node, Preview out, int depth) {
        if (depth > 100) throw new PreviewLimitException("公式内容嵌套过深，请下载原文件阅读");
        String local = local(node);
        switch (local) {
            case "t" -> out.add(escape(text(node)));
            case "f" -> {
                out.add("("); renderMathChildren(child(node, "num"), out, depth); out.add(")/(");
                renderMathChildren(child(node, "den"), out, depth); out.add(")");
            }
            case "sSup", "sSub", "sSubSup" -> {
                renderMathChildren(child(node, "e"), out, depth);
                if (!local.equals("sSup")) { out.add("<sub>"); renderMathChildren(child(node, "sub"), out, depth); out.add("</sub>"); }
                if (!local.equals("sSub")) { out.add("<sup>"); renderMathChildren(child(node, "sup"), out, depth); out.add("</sup>"); }
            }
            case "rad" -> {
                if (child(node, "deg") != null) { out.add("<sup>"); renderMathChildren(child(node, "deg"), out, depth); out.add("</sup>"); }
                out.add("√("); renderMathChildren(child(node, "e"), out, depth); out.add(")");
            }
            case "nary" -> {
                String symbol = attr(child(child(node, "naryPr"), "chr"), "val");
                out.add(escape(symbol.isEmpty() ? "∫" : symbol));
                if (child(node, "sub") != null) { out.add("<sub>"); renderMathChildren(child(node, "sub"), out, depth); out.add("</sub>"); }
                if (child(node, "sup") != null) { out.add("<sup>"); renderMathChildren(child(node, "sup"), out, depth); out.add("</sup>"); }
                renderMathChildren(child(node, "e"), out, depth);
            }
            case "d" -> {
                Node props = child(node, "dPr");
                String begin = attr(child(props, "begChr"), "val"), end = attr(child(props, "endChr"), "val");
                out.add(escape(begin.isEmpty() ? "(" : begin)); renderMathChildren(node, out, depth); out.add(escape(end.isEmpty() ? ")" : end));
            }
            case "m" -> {
                out.add("["); renderMathChildren(node, out, depth); out.add("]");
            }
            case "mr" -> { renderMathChildren(node, out, depth); out.add("; "); }
            default -> {
                if (!local.endsWith("Pr")) renderMathChildren(node, out, depth);
            }
        }
    }

    private void renderMathChildren(Node node, Preview out, int depth) {
        if (node == null) return;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) renderMath(child, out, depth + 1);
        }
    }

    private void renderLegacy(InputStream in, Preview out) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document html = factory.newDocumentBuilder().newDocument();
        try (HWPFDocument doc = new HWPFDocument(in)) {
            WordToHtmlConverter converter = new WordToHtmlConverter(html);
            converter.setPicturesManager((bytes, type, name, width, height) -> out.image(bytes, type.getMime()));
            converter.processDocument(doc);
            Map<String, String> styles = new LinkedHashMap<>();
            var sheets = html.getElementsByTagName("style");
            for (int i = 0; i < sheets.getLength(); i++) {
                Matcher rules = CSS_RULE.matcher(text(sheets.item(i)));
                while (rules.find()) styles.put(rules.group(1), safeCss(rules.group(2)));
            }
            Node body = html.getElementsByTagName("body").item(0);
            out.add("<div class=\"word-document\">");
            if (body != null) sanitizeLegacy(body, styles, out, 0);
            out.add("</div>");
            out.warnings.add("旧版 DOC 的嵌入公式、批注、修订及绘图对象可能无法完整展示；脚注和普通表格按原文件内容预览，请下载原文件核对复杂内容。");
        }
    }

    private void sanitizeLegacy(Node node, Map<String, String> styles, Preview out, int depth) {
        if (depth > 100) throw new PreviewLimitException("Word 内容嵌套过深，请下载原文件阅读");
        if (node.getNodeType() == Node.TEXT_NODE) { out.add(escape(node.getNodeValue())); return; }
        if (!(node instanceof Element element)) return;
        String tag = element.getTagName().toLowerCase(Locale.ROOT);
        if (Set.of("script", "style", "object", "iframe", "embed", "link", "meta").contains(tag)) return;
        boolean allowed = HTML_TAGS.contains(tag);
        StringBuilder attributes = new StringBuilder();
        StringBuilder css = new StringBuilder(safeCss(element.getAttribute("style")));
        for (String cls : element.getAttribute("class").split("\\s+")) css.append(styles.getOrDefault(cls, ""));
        if (!css.isEmpty()) attributes.append(" style=\"").append(escape(css.toString())).append('"');
        String anchor = element.getAttribute("id");
        if (anchor.isEmpty()) anchor = element.getAttribute("name");
        if (anchor.matches("[\\p{L}\\p{N}_-]{1,120}")) attributes.append(" id=\"").append(escape(anchor)).append('"');
        if (tag.equals("a")) {
            String href = safeLink(element.getAttribute("href"));
            if (!href.isEmpty()) attributes.append(" href=\"").append(escape(href)).append("\" rel=\"noopener noreferrer\"");
        }
        if (tag.equals("img")) {
            String src = element.getAttribute("src");
            if (!src.matches("data:image/(?:png|jpeg|gif|bmp|webp);base64,[A-Za-z0-9+/=]+")) return;
            attributes.append(" src=\"").append(src).append("\" alt=\"文档插图\"");
        }
        for (String prop : Set.of("colspan", "rowspan")) {
            String value = element.getAttribute(prop);
            if (value.matches("[1-9][0-9]{0,2}")) attributes.append(' ').append(prop).append("=\"").append(value).append('"');
        }
        if (allowed) out.add("<" + tag + attributes + ">");
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) sanitizeLegacy(child, styles, out, depth + 1);
        if (allowed && !Set.of("br", "img").contains(tag)) out.add("</" + tag + ">");
    }

    private static String safeCss(String css) {
        StringBuilder result = new StringBuilder();
        for (String declaration : css.split(";")) {
            int colon = declaration.indexOf(':');
            if (colon < 0) continue;
            String name = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = declaration.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            if (!Set.of("color", "background-color", "font-size", "font-weight", "font-style", "text-decoration", "text-align", "vertical-align", "white-space",
                    "margin", "margin-left", "margin-right", "margin-top", "margin-bottom", "padding", "padding-left", "padding-right", "padding-top", "padding-bottom",
                    "border", "border-top", "border-bottom", "border-left", "border-right", "border-collapse", "width", "height", "max-width", "text-indent", "line-height").contains(name)) continue;
            if (value.length() > 100 || !value.matches("[#a-z0-9.%,\\s-]+") || value.contains("url") || value.contains("expression")) continue;
            result.append(name).append(':').append(value).append(';');
        }
        return result.toString();
    }

    private static String safeLink(String url) {
        if (url == null) return "";
        String value = url.trim();
        if (value.chars().anyMatch(c -> c < 32 || c == 127)) return "";
        return value.matches("(?i)(?:https?://|mailto:|#).*?") ? value : "";
    }

    private static void validateZip(Path path) throws IOException {
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            while (zip.getNextEntry() != null) {
                if (++entries > 2048) throw new PreviewLimitException("Word 文件内部对象过多，请下载原文件阅读");
                long entryBytes = 0;
                for (int count; (count = zip.read(buffer)) >= 0;) {
                    total += count; entryBytes += count;
                    if (total > 64L * 1024 * 1024 || entryBytes > 32L * 1024 * 1024) {
                        throw new PreviewLimitException("Word 解压后的内容过大，请下载原文件阅读");
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static Node child(Node node, String name) {
        if (node == null) return null;
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeType() == Node.ELEMENT_NODE && local(n).equals(name)) return n;
        return null;
    }

    private static Node descendant(Node node, String name) {
        return descendant(node, name, 0);
    }

    private static Node descendant(Node node, String name, int depth) {
        if (node == null) return null;
        if (depth > 100) throw new PreviewLimitException("Word 绘图对象嵌套过深，请下载原文件阅读");
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (local(n).equals(name)) return n;
            Node found = descendant(n, name, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static String local(Node node) {
        String local = node.getLocalName();
        return local == null ? node.getNodeName().replaceFirst("^.*:", "") : local;
    }

    private static String attr(Node node, String name) {
        return attrNs(node, node != null && M.equals(node.getNamespaceURI()) ? M : W, name);
    }

    private static String attrNs(Node node, String ns, String name) {
        return node instanceof Element element ? element.getAttributeNS(ns, name) : "";
    }

    private static boolean on(Node props, String name) {
        Node node = child(props, name);
        return node != null && !Set.of("false", "0", "off").contains(attr(node, "val"));
    }

    private static String text(Node node) {
        if (node == null) return "";
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) return node.getNodeValue();
        StringBuilder text = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) text.append(text(child));
        return text.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final class Preview {
        private final StringBuilder html = new StringBuilder();
        private final Set<String> warnings = new LinkedHashSet<>();
        private final Map<String, Integer> listCounters = new LinkedHashMap<>();
        private POIXMLDocumentPart owner;
        private int imageBytes;

        private void add(String value) {
            if ((long) html.length() + value.length() > MAX_HTML_CHARS) throw new PreviewLimitException("Word 预览内容过大，请下载原文件阅读");
            html.append(value);
        }

        private String image(byte[] bytes, String type) {
            if (type == null || !Set.of("image/png", "image/jpeg", "image/gif", "image/bmp", "image/webp").contains(type.toLowerCase(Locale.ROOT))) {
                warnings.add("部分插图是浏览器不支持的图像格式（例如 WMF/EMF），请下载原文件查看这些插图。");
                return "";
            }
            if (bytes.length > MAX_IMAGE_BYTES || (long) imageBytes + bytes.length > MAX_ALL_IMAGE_BYTES) {
                warnings.add("部分插图超过预览大小限制（单图 2 MiB、总计 6 MiB），请下载原文件查看完整插图。");
                return "";
            }
            imageBytes += bytes.length;
            return "data:" + type.toLowerCase(Locale.ROOT) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }

    private static final class PreviewLimitException extends IllegalArgumentException {
        private PreviewLimitException(String message) { super(message); }
    }
}
