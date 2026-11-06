package org.dyh.learnhub.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.net.URI;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.dyh.learnhub.service.OmmlLatexConverter.M;
import static org.dyh.learnhub.service.OmmlLatexConverter.W;
import static org.dyh.learnhub.service.OmmlLatexConverter.local;

/** Word extraction in document order, including equations that POI's plain-text extractor omits. */
@Service
public class WordLayoutExtractor {
    private static final int MAX_PART_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PARTS = 2_048;
    private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_DEPTH = 100;
    private static final int MAX_CHARS = DocumentTextService.MAX_TEXT_CHARS;
    private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String HARD_BREAK = "\u0003hardbreak\u0004", PAGE_BREAK = "\u0003pagebreak\u0004";
    private static final Pattern HEADING = Pattern.compile("(?i)(?:heading|标题)[ _-]?([1-6])");

    public PdfLayoutExtractor.Layout extract(Path path) throws IOException {
        Parsed parsed = parse(path);
        List<PdfLayoutExtractor.PageLayout> pages = parsed.blocks.isEmpty() ? List.of()
                : List.of(new PdfLayoutExtractor.PageLayout(1, 1, List.copyOf(parsed.blocks)));
        return new PdfLayoutExtractor.Layout(parsed.blocks.isEmpty() ? "empty" : "ok",
                parsed.warnings.isEmpty() ? null : String.join("；", parsed.warnings), pages,
                parsed.chars, pages.size());
    }

    /** Structured ingestion shares the reader's OMML parser and the cell-aware Markdown table serializer. */
    public DocumentIngestionService.Extraction extractForIngestion(Path path) throws IOException {
        Parsed parsed = parse(path, true);
        List<DocumentIngestionService.Block> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>(parsed.warnings);
        for (int i = 0; i < parsed.blocks.size(); i++) {
            var block = parsed.blocks.get(i);
            boolean figure = "figure".equals(block.type());
            boolean structural = figure || "page_break".equals(block.type()) || block.text().isBlank();
            boolean uncertain = block.latexStatus() != null && !Set.of("restored", "recognized").contains(block.latexStatus());
            if (uncertain && !warnings.contains("部分 Word 公式未完整还原，请对照原文；相关块未进入索引"))
                warnings.add("部分 Word 公式未完整还原，请对照原文；相关块未进入索引");
            blocks.add(new DocumentIngestionService.Block("p1-b" + (i + 1), block.type(), block.text(), block.text(),
                    1, null, 0, 0, null, structural ? "non_text" : uncertain ? "uncertain_formula" : "accepted",
                    !structural && !uncertain, -1, -1, block.level(), block.src(), block.latex(), block.latexStatus(),
                    block.latexMessage(), parsed.searchTables.get(i), -1, -1, parsed.presentation.get(i)));
        }
        return DocumentFormatAdapter.wrap(path, List.of(new DocumentIngestionService.Page(1, 0, 0, "none", "text", blocks)), warnings, false);
    }

    /** Search text retains block structure while sharing equation conversion with the reading view. */
    public String plainText(Path path) throws IOException {
        Parsed parsed = parse(path, true);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parsed.blocks.size(); i++) {
            var block = parsed.blocks.get(i);
            if (block.type().equals("formula") && block.latex() != null) text.append("$$").append(block.latex()).append("$$");
            else if (block.type().equals("heading")) text.append("#".repeat(Math.max(1, Math.min(6, block.level()))))
                    .append(' ').append(block.text().replace('\n', ' '));
            else if (parsed.searchTables.containsKey(i)) text.append(parsed.searchTables.get(i));
            else text.append(block.text());
            if (block.latexStatus() != null && !block.latexStatus().equals("restored")) {
                if (parsed.searchTables.containsKey(i)) text.append("\n\n");
                text.append(" [公式未完整还原：").append(block.latexMessage()).append(']');
            }
            text.append("\n\n");
        }
        return DocumentTextService.tidy(text.toString());
    }

    private Parsed parse(Path path) throws IOException {
        return parse(path, false);
    }

    private Parsed parse(Path path, boolean forSearch) throws IOException {
        Parsed out = new Parsed(forSearch);
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<? extends ZipEntry> entries = zip.stream().toList();
            if (entries.size() > MAX_PARTS) throw new IOException("Word 文件内部对象过多，未抽取正文");
            long declaredBytes = 0;
            for (ZipEntry entry : entries) {
                if (entry.getSize() > MAX_EXPANDED_BYTES) throw new IOException("Word 解压内容超过上限");
                declaredBytes += Math.max(0, entry.getSize());
                if (declaredBytes > MAX_EXPANDED_BYTES) throw new IOException("Word 解压内容超过上限");
            }
            Document document = read(zip, "word/document.xml", out, true);
            Document styles = read(zip, "word/styles.xml", out, false);
            if (styles != null) readStyles(styles.getDocumentElement(), out);
            if (forSearch) readRelationships(zip, entries, out);
            Node body = wordChild(document.getDocumentElement(), "body");
            if (body == null) throw new IOException("Word 文件缺少正文结构");
            walk(body, out, 0, null);
            notes(zip, "word/footnotes.xml", "footnote", "脚注", out);
            notes(zip, "word/endnotes.xml", "endnote", "尾注", out);
            notes(zip, "word/comments.xml", "comment", "批注", out);
            for (ZipEntry entry : entries.stream().filter(e -> e.getName().matches("word/(?:header|footer)\\d+\\.xml"))
                    .sorted(Comparator.comparing(ZipEntry::getName)).toList()) {
                Document part = read(zip, entry.getName(), out, false);
                if (part != null) {
                    out.add(new PdfLayoutExtractor.Block("meta", entry.getName().contains("header") ? "页眉" : "页脚", 0));
                    out.currentPart = entry.getName();
                    walk(part.getDocumentElement(), out, 0, "meta");
                }
            }
        }
        return out;
    }

    private Document read(ZipFile zip, String name, Parsed out, boolean required) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            if (required) throw new IOException("Word 文件缺少 " + name);
            return null;
        }
        if (entry.getSize() > MAX_PART_BYTES) throw new IOException("Word XML 内容超过上限");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] data = input.readNBytes(MAX_PART_BYTES + 1);
            out.expandedBytes += data.length;
            if (data.length > MAX_PART_BYTES || out.expandedBytes > MAX_EXPANDED_BYTES)
                throw new IOException("Word XML 解压内容超过上限");
            return secureParse(data);
        }
    }

    static Document secureParse(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException e) { throw new IOException("Word XML 无法安全解析", e); }
    }

    private void walk(Node node, Parsed out, int depth, String override) throws IOException {
        checkDepth(depth);
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            if (MC.equals(n.getNamespaceURI()) && local(n).equals("AlternateContent")) {
                Node choice = firstElement(n, "Choice"), fallback = firstElement(n, "Fallback");
                walk(choice != null ? choice : fallback != null ? fallback : n, out, depth + 1, override);
                continue;
            }
            if (W.equals(n.getNamespaceURI()) && local(n).equals("p")) paragraph(n, out, depth + 1, override);
            else if (W.equals(n.getNamespaceURI()) && local(n).equals("tbl")) table(n, out, depth + 1, override);
            else if (M.equals(n.getNamespaceURI()) && Set.of("oMath", "oMathPara").contains(local(n)))
                formula(n, out, override);
            else if (out.forSearch && W.equals(n.getNamespaceURI()) && local(n).equals("sectPr")) {
                String kind = attribute(wordChild(n, "type"), "val");
                if (Set.of("nextPage", "evenPage", "oddPage").contains(kind)) pageBreak(out);
            } else if (!(W.equals(n.getNamespaceURI()) && local(n).endsWith("Pr"))) walk(n, out, depth + 1, override);
        }
    }

    private void paragraph(Node node, Parsed out, int depth, String override) throws IOException {
        Node props = wordChild(node, "pPr");
        String style = attribute(wordChild(props, "pStyle"), "val");
        String level = attribute(wordChild(props, "outlineLvl"), "val");
        if (level.isEmpty()) level = out.outlineLevels.getOrDefault(style, "");
        String styleName = out.styleNames.getOrDefault(style, style);
        var match = HEADING.matcher(styleName);
        int heading = level.matches("[0-5]") ? Integer.parseInt(level) + 1 : match.find() ? Integer.parseInt(match.group(1)) : 0;
        String type = override != null ? override : heading > 0 ? "heading" : wordChild(props, "numPr") != null ? "bullet" : "para";
        if (out.forSearch) {
            if (override == null && styleName.matches("(?i).*(?:quote|引用|引文).*")) type = "quote";
            ingestionParagraph(node, out, depth, props, type, heading);
            return;
        }
        Inline inline = new Inline();
        inline(node, inline, out, depth);
        // tidy().trim() removes our XML-impossible control delimiters at paragraph edges.
        // Split image markers first, then normalize each actual text segment independently.
        String value = inline.images.isEmpty() ? DocumentTextService.tidy(inline.text.toString()) : inline.text.toString();
        if (value.isEmpty()) return;
        if (!inline.images.isEmpty()) {
            int from = 0;
            for (var image : inline.images.entrySet()) {
                int at = value.indexOf(image.getKey(), from);
                if (at < 0) continue;
                String before = DocumentTextService.tidy(value.substring(from, at));
                if (!before.isEmpty()) out.add(new PdfLayoutExtractor.Block(type, before, heading, null, null, null, null,
                        inline.formulas.isEmpty() ? null : inline.status(), inline.message()));
                out.add(new PdfLayoutExtractor.Block("figure", "", 0, image.getValue().source()));
                from = at + image.getKey().length();
            }
            String after = DocumentTextService.tidy(value.substring(from));
            if (!after.isEmpty()) out.add(new PdfLayoutExtractor.Block(type, after, heading, null, null, null, null,
                    inline.formulas.isEmpty() ? null : inline.status(), inline.message()));
        } else if (inline.formulas.size() == 1 && inline.prose.toString().isBlank() && override == null) {
            var math = inline.formulas.getFirst();
            out.add(mathBlock(math, "formula", 0));
        } else {
            out.add(new PdfLayoutExtractor.Block(type, value, heading, null, null, null, null,
                    inline.formulas.isEmpty() ? null : inline.status(), inline.message()));
        }
    }

    private void ingestionParagraph(Node node, Parsed out, int depth, Node props, String type, int heading) throws IOException {
        if (enabled(wordChild(props, "pageBreakBefore"))) pageBreak(out);
        Inline content = new Inline(); content.rootParagraph = node; inline(node, content, out, depth);
        String group = "word-p" + (++out.paragraphs);
        String align = attribute(wordChild(props, "jc"), "val");
        if (!Set.of("left", "right", "center", "justify").contains(align)) align = null;
        String[] pieces = content.text.toString().split(Pattern.quote(PAGE_BREAK), -1);
        boolean first = true;
        for (int part = 0; part < pieces.length; part++) {
            String value = pieces[part]; int from = 0;
            boolean pureMath = content.images.isEmpty() && content.markups.isEmpty() && content.formulas.size() == 1
                    && content.prose.toString().isBlank() && !value.contains(HARD_BREAK);
            if (pureMath && !value.isBlank()) {
                int at = out.blocks.size(); out.add(mathBlock(content.formulas.getFirst(), "formula", 0));
                out.presentation.put(at, new DocumentIngestionService.Presentation(group, "block", null, null, align, null));
                first = false;
            } else {
                for (var entry : content.images.entrySet()) {
                    int at = value.indexOf(entry.getKey(), from); if (at < 0) continue;
                    first = textFragment(value.substring(from, at), content, out, group, align, type, heading, first);
                    int index = out.blocks.size(); out.add(new PdfLayoutExtractor.Block("figure", "", 0, entry.getValue().source()));
                    out.presentation.put(index, entry.getValue().presentation(group, entry.getValue().placement()));
                    from = at + entry.getKey().length();
                }
                first = textFragment(value.substring(from), content, out, group, align, type, heading, first);
            }
            if (part < pieces.length - 1) { pageBreak(out); group = "word-p" + (++out.paragraphs); first = true; }
        }
        Node section = wordChild(props, "sectPr");
        if (section != null && !Set.of("continuous", "nextColumn").contains(attribute(wordChild(section, "type"), "val"))) pageBreak(out);
    }

    private boolean textFragment(String source, Inline content, Parsed out, String group, String align,
                                 String type, int heading, boolean first) throws IOException {
        if (source.isEmpty()) return first;
        String raw = visible(source, content);
        String markdown = serialize(source, content, false);
        if (first && type.equals("heading")) markdown = "#".repeat(Math.max(1, Math.min(6, heading))) + " " + markdown;
        else if (first && type.equals("bullet")) markdown = "- " + markdown;
        else if (first && type.equals("quote")) markdown = "> " + markdown.replace("\n", "\n> ");
        int index = out.blocks.size();
        out.add(new PdfLayoutExtractor.Block(type, raw, heading, null, null, null, null,
                content.formulas.isEmpty() ? null : content.status(), content.message()));
        out.searchTables.put(index, markdown);
        out.presentation.put(index, new DocumentIngestionService.Presentation(group, "inline", null, null, align, null));
        return false;
    }

    private static String visible(String source, Inline content) {
        String value = source.replace(HARD_BREAK, "\n").replace(PAGE_BREAK, "\n");
        for (var entry : content.markups.entrySet()) value = value.replace(entry.getKey(), entry.getValue().visible());
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String serialize(String source, Inline content, boolean cell) {
        String value = DocumentMarkdownWriter.inlineText(source);
        for (var entry : content.markups.entrySet()) value = value.replace(entry.getKey(), entry.getValue().markdown());
        return value.replace(HARD_BREAK, cell ? "<br>" : "  \n").replace(PAGE_BREAK, cell ? "<br>" : "\n\n");
    }

    private static void pageBreak(Parsed out) throws IOException {
        if (out.blocks.isEmpty() || !out.blocks.getLast().type().equals("page_break")) out.add(new PdfLayoutExtractor.Block("page_break", "", 0));
    }

    private static boolean enabled(Node property) {
        if (property == null) return false;
        return !Set.of("0", "false", "off").contains(attribute(property, "val").toLowerCase(java.util.Locale.ROOT));
    }

    private void readRelationships(ZipFile zip, List<? extends ZipEntry> entries, Parsed out) throws IOException {
        for (ZipEntry entry : entries) {
            String name = entry.getName();
            int folder = name.lastIndexOf("/_rels/");
            if (!name.startsWith("word/") || folder < 0 || !name.endsWith(".rels")) continue;
            String owner = name.substring(0, folder + 1) + name.substring(folder + 7, name.length() - 5);
            Document document = read(zip, name, out, false); if (document == null) continue;
            Map<String, String> links = new LinkedHashMap<>();
            var relationships = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship");
            for (int i = 0; i < relationships.getLength(); i++) {
                Element relation = (Element) relationships.item(i);
                if (!relation.getAttribute("Type").endsWith("/hyperlink")) continue;
                String target = safeLink(relation.getAttribute("Target"));
                if (!target.isEmpty()) links.put(relation.getAttribute("Id"), target);
            }
            out.links.put(owner, links);
        }
    }

    private static String safeLink(String value) {
        try {
            if (value == null || value.length() > 2048 || value.matches("(?s).*[\\r\\n<>\"\\\\].*")) return "";
            URI uri = URI.create(value);
            if (Set.of("http", "https").contains(String.valueOf(uri.getScheme()).toLowerCase(java.util.Locale.ROOT))
                    && uri.getHost() != null && uri.getUserInfo() == null) return value.replace("(", "%28").replace(")", "%29");
        } catch (IllegalArgumentException ignored) { }
        return "";
    }

    private static String bookmarkId(String name) {
        if (name.matches("[A-Za-z0-9_.:-]{1,80}")) return "word-bookmark-" + name;
        try { return "word-bookmark-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))).substring(0, 32); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String fieldTarget(String instruction) {
        var localLink = Pattern.compile("(?i)^\\s*(?:REF|PAGEREF)\\s+(?:\"([^\"]+)\"|([^\\s]+)).*").matcher(instruction);
        if (localLink.matches()) return "#" + bookmarkId(localLink.group(1) != null ? localLink.group(1) : localLink.group(2));
        var hyperlink = Pattern.compile("(?i)^\\s*HYPERLINK\\s+(?:\"([^\"]+)\"|([^\\s]+)).*").matcher(instruction);
        return hyperlink.matches() ? safeLink(hyperlink.group(1) != null ? hyperlink.group(1) : hyperlink.group(2)) : "";
    }

    private static void field(Node node, Inline target) throws IOException {
        String kind = attribute(node, "fldCharType");
        if (kind.equals("begin")) target.fields.push(new Field());
        else if (kind.equals("separate") && !target.fields.isEmpty()) {
            Field field = target.fields.peek(); field.separated = true; field.destination = fieldTarget(field.instruction.toString());
            if (!field.destination.isEmpty()) target.markup("[", "");
        } else if (kind.equals("end") && !target.fields.isEmpty()) {
            Field field = target.fields.pop(); if (field.separated && !field.destination.isEmpty()) target.markup("](" + field.destination + ")", "");
        }
    }

    private record ImageRef(String source, Double width, Double height, String placement, String align) {
        DocumentIngestionService.Presentation presentation(String group, String mode) {
            return new DocumentIngestionService.Presentation(group, mode, width, height, align, source);
        }
    }

    private static ImageRef imageRef(Node node, String source) {
        Node container = node;
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (Set.of("inline", "anchor", "shape").contains(local(parent))) { container = parent; break; }
            if (W.equals(parent.getNamespaceURI()) && local(parent).equals("p")) break;
        }
        Node extent = descendant(container, "extent");
        Double width = number(extent, "cx", 1.0 / 9525), height = number(extent, "cy", 1.0 / 9525);
        if (local(container).equals("shape") && container instanceof Element shape) {
            if (width == null) width = cssDimension(shape.getAttribute("style"), "width");
            if (height == null) height = cssDimension(shape.getAttribute("style"), "height");
        }
        Node position = descendant(container, "positionH"), alignment = descendant(position, "align");
        String align = alignment == null ? null : alignment.getTextContent().strip();
        if (!Set.of("left", "right", "center").contains(String.valueOf(align))) align = null;
        return new ImageRef(source, width, height, local(container).equals("anchor") ? "float" : "inline", align);
    }

    private static Node descendant(Node node, String wanted) {
        return descendant(node, wanted, 0);
    }
    private static Node descendant(Node node, String wanted, int depth) {
        if (depth > MAX_DEPTH) return null;
        if (node == null) return null;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            if (local(child).equals(wanted)) return child;
            Node nested = descendant(child, wanted, depth + 1); if (nested != null) return nested;
        }
        return null;
    }
    private static Double number(Node node, String attribute, double factor) {
        if (!(node instanceof Element element)) return null;
        try { double number = Double.parseDouble(element.getAttribute(attribute)) * factor; return Double.isFinite(number) && number > 0 && number <= 20000 ? number : null; }
        catch (NumberFormatException invalid) { return null; }
    }
    private static Double cssDimension(String style, String key) {
        var match = Pattern.compile("(?i)(?:^|;)\\s*" + key + "\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(pt|px|in|cm|mm)").matcher(style);
        if (!match.find()) return null;
        double value = Double.parseDouble(match.group(1)) * switch (match.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "pt" -> 96.0 / 72; case "in" -> 96; case "cm" -> 96 / 2.54; case "mm" -> 96 / 25.4; default -> 1;
        };
        return Double.isFinite(value) && value > 0 && value <= 20000 ? value : null;
    }
    private static String imageMarkdown(ImageRef image) {
        return "<img src=\"" + image.source() + "\" alt=\"原图\"" + (image.width() == null ? "" : " width=\"" + image.width() + "\"")
                + (image.height() == null ? "" : " height=\"" + image.height() + "\"") + ">";
    }

    private void inline(Node node, Inline target, Parsed out, int depth) throws IOException {
        checkDepth(depth);
        if (node.getNodeType() != Node.ELEMENT_NODE) return;
        String name = local(node);
        if (out.forSearch && W.equals(node.getNamespaceURI())) {
            if (name.equals("hyperlink")) {
                String id = attributeNs(node, REL, "id"), anchor = attribute(node, "anchor");
                String destination = !anchor.isBlank() ? "#" + bookmarkId(anchor) : out.links.getOrDefault(out.currentPart, Map.of()).getOrDefault(id, "");
                if (!destination.isEmpty()) target.markup("[", "");
                for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) inline(child, target, out, depth + 1);
                if (!destination.isEmpty()) target.markup("](" + destination + ")", "");
                return;
            }
            if (name.equals("bookmarkStart")) {
                String bookmark = attribute(node, "name");
                if (!bookmark.isBlank()) target.markup("<a id=\"" + bookmarkId(bookmark) + "\"></a>", "");
                return;
            }
            if (name.equals("br") || name.equals("cr")) {
                target.append(name.equals("br") && attribute(node, "type").equals("page") ? PAGE_BREAK : HARD_BREAK, true); return;
            }
            if (name.equals("footnoteReference") || name.equals("endnoteReference")) {
                String id = attribute(node, "id");
                if (id.matches("[0-9]{1,10}")) target.markup("[^" + (name.equals("footnoteReference") ? "fn-" : "en-") + id + "]", "[" + (name.equals("footnoteReference") ? "脚注" : "尾注") + id + "]");
                return;
            }
            if (name.equals("fldChar")) { field(node, target); return; }
            if (name.equals("instrText")) {
                if (!target.fields.isEmpty() && !target.fields.peek().separated) target.fields.peek().instruction.append(node.getTextContent());
                return;
            }
            if (name.equals("fldSimple")) {
                String destination = fieldTarget(attribute(node, "instr"));
                if (!destination.isEmpty()) target.markup("[", "");
                for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) inline(child, target, out, depth + 1);
                if (!destination.isEmpty()) target.markup("](" + destination + ")", "");
                return;
            }
        }
        if (Set.of("blip", "imagedata").contains(name)) {
            String relationship = attributeNs(node, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed");
            if (relationship.isEmpty()) relationship = attributeNs(node, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
            if (relationship.isEmpty()) relationship = attributeNs(node, "http://schemas.openxmlformats.org/officeDocument/2006/relationships", "link");
            if (!relationship.isEmpty() && relationship.matches("[A-Za-z0-9_.-]{1,100}"))
                target.image(imageRef(node, "word:" + out.currentPart + "#" + relationship));
            return;
        }
        if (M.equals(node.getNamespaceURI()) && Set.of("oMath", "oMathPara").contains(name)) {
            var math = out.converter.convert(node);
            target.formulas.add(math);
            String value = math.latex() == null ? "[公式原始字符：" + math.originalText() + "]"
                    : (name.equals("oMathPara") ? "$$" : "\\(") + math.latex() + (name.equals("oMathPara") ? "$$" : "\\)");
            target.append(value, false);
            return;
        }
        if (W.equals(node.getNamespaceURI())) {
            if (name.endsWith("Pr") || Set.of("instrText", "delInstrText", "sectPr").contains(name)) return;
            switch (name) {
                case "t", "delText" -> { target.append(node.getTextContent(), true); return; }
                case "tab", "ptab" -> { target.append("\t", true); return; }
                case "br", "cr" -> { target.append("\n", true); return; }
                case "noBreakHyphen" -> { target.append("‑", true); return; }
                case "softHyphen" -> { target.append("\u00ad", true); return; }
                case "footnoteReference", "endnoteReference", "commentReference" -> {
                    String kind = name.equals("footnoteReference") ? "脚注" : name.equals("endnoteReference") ? "尾注" : "批注";
                    target.append("[" + kind + attribute(node, "id") + "]", true); return;
                }
                default -> { }
            }
        }
        if (MC.equals(node.getNamespaceURI()) && name.equals("AlternateContent")) {
            Node choice = firstElement(node, "Choice"), fallback = firstElement(node, "Fallback");
            Node selected = choice != null ? choice : fallback != null ? fallback : node;
            for (Node n = selected.getFirstChild(); n != null; n = n.getNextSibling()) inline(n, target, out, depth + 1);
            return;
        }
        if (name.equals("OLEObject") && node instanceof Element e) {
            String program = e.getAttribute("ProgID");
            if (program.startsWith("Equation") || program.contains("MathType")) {
                target.append("[嵌入式旧版公式，请对照原文]", false);
                target.formulas.add(new OmmlLatexConverter.Result(null, "unavailable", "旧版 OLE/MathType 公式不含 OMML，无法自动还原", ""));
            }
        }
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) inline(n, target, out, depth + 1);
        if (W.equals(node.getNamespaceURI()) && name.equals("p") && depth > 1 && node != target.rootParagraph) target.append("\n", true);
    }

    private void table(Node node, Parsed out, int depth, String override) throws IOException {
        checkDepth(depth);
        Inline contents = new Inline();
        List<ImageRef> images = new ArrayList<>();
        Map<String, String> trusted = new LinkedHashMap<>(), visible = new LinkedHashMap<>();
        List<List<String>> rows = out.forSearch ? new ArrayList<>() : null;
        for (Node row : wrappedWords(node, "tr", depth)) {
            boolean first = true;
            List<String> cells = out.forSearch ? new ArrayList<>() : null;
            for (Node cell : wrappedWords(row, "tc", depth + 1)) {
                if (!first) contents.append("\t", true);
                Inline value = new Inline(); inline(cell, value, out, depth + 1);
                String cellText = value.text.toString().strip();
                for (var image : value.images.entrySet()) {
                    String marker = "\u0005cell" + trusted.size() + "\u0006";
                    trusted.put(marker, imageMarkdown(image.getValue())); visible.put(marker, "[图片]");
                    cellText = cellText.replace(image.getKey(), out.forSearch ? marker : "![图片](" + image.getValue().source() + ")");
                    images.add(image.getValue());
                }
                if (out.forSearch) {
                    for (var markup : value.markups.entrySet()) {
                        String marker = "\u0005cell" + trusted.size() + "\u0006";
                        trusted.put(marker, markup.getValue().markdown()); visible.put(marker, markup.getValue().visible());
                        cellText = cellText.replace(markup.getKey(), marker);
                    }
                    cellText = cellText.replace(HARD_BREAK, "\n").replace(PAGE_BREAK, "\n");
                }
                contents.append(cellText, true);
                if (cells != null) cells.add(cellText);
                contents.formulas.addAll(value.formulas);
                first = false;
            }
            if (rows != null && !cells.isEmpty()) rows.add(cells);
            contents.append("\n", true);
        }
        String value = contents.text.toString();
        for (var entry : visible.entrySet()) value = value.replace(entry.getKey(), entry.getValue());
        value = DocumentTextService.tidy(value);
        if (!value.isEmpty()) {
            int blockIndex = out.blocks.size();
            out.add(new PdfLayoutExtractor.Block(override == null ? "table" : override, value, 0,
                    null, null, null, null, contents.formulas.isEmpty() ? null : contents.status(), contents.message()));
            if (rows != null) {
                String markdown = markdownTable(rows);
                for (var entry : trusted.entrySet()) markdown = markdown.replace(entry.getKey(), entry.getValue());
                out.searchTables.put(blockIndex, markdown);
            }
            for (ImageRef image : images) {
                int imageIndex = out.blocks.size();
                out.add(new PdfLayoutExtractor.Block("figure", "", 0, image.source()));
                // The image is already in its cell; this block supplies the media resolver with its relationship.
                if (rows != null) out.searchTables.put(imageIndex, "");
                if (out.forSearch) out.presentation.put(imageIndex, image.presentation("p1-b" + (blockIndex + 1), "resolver_only"));
            }
        }
    }

    /** Read cells directly: the reader's tab text cannot distinguish cell line breaks from row breaks. */
    static String markdownTable(List<List<String>> rows) throws IOException {
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) return "";
        if ((long) columns * (rows.size() + 1) > MAX_CHARS)
            throw new IOException("Word 表格补齐列后超过抽取长度上限");
        StringBuilder table = new StringBuilder();
        for (int row = 0; row < rows.size(); row++) {
            table.append('|');
            for (int col = 0; col < columns; col++) {
                String cell = col < rows.get(row).size() ? rows.get(row).get(col) : "";
                // Entities also protect a pipe immediately preceded by a LaTeX backslash.
                table.append(' ').append(cell.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                        .replace("|", "&#124;").replace("\r\n", "\n").replace('\r', '\n')
                        .replace("\n", "<br>").replace("\t", "&#9;")).append(" |");
            }
            if (row == 0) table.append('\n').append('|').append(" --- |".repeat(columns));
            if (row < rows.size() - 1) table.append('\n');
        }
        return table.toString();
    }

    private void formula(Node node, Parsed out, String override) throws IOException {
        out.add(mathBlock(out.converter.convert(node), override == null ? "formula" : override, 0));
    }

    private PdfLayoutExtractor.Block mathBlock(OmmlLatexConverter.Result result, String type, int level) {
        return new PdfLayoutExtractor.Block(type, result.originalText().isBlank() ? "[公式]" : result.originalText(), level,
                null, null, null, result.latex(), result.status(), result.message());
    }

    private void notes(ZipFile zip, String path, String itemName, String label, Parsed out) throws IOException {
        Document document = read(zip, path, out, false);
        if (document == null) return;
        String previousPart = out.currentPart;
        out.currentPart = path;
        for (Node note : directWords(document.getDocumentElement(), itemName)) {
            String id = attribute(note, "id");
            if (!itemName.equals("comment") && id.matches("-?\\d+") && (id.startsWith("-") || id.matches("0+"))) continue;
            String author = attribute(note, "author");
            if (out.forSearch && !itemName.equals("comment")) {
                int first = out.blocks.size(); walk(note, out, 0, "note");
                String marker = "[^" + (itemName.equals("footnote") ? "fn-" : "en-") + id + "]: ";
                for (int index = first; index < out.blocks.size(); index++) {
                    var block = out.blocks.get(index);
                    String markdown = out.searchTables.get(index);
                    if (markdown == null) markdown = block.latex() != null ? "$$\n" + block.latex() + "\n$$"
                            : DocumentMarkdownWriter.inlineText(block.text());
                    boolean sameGroup = index > first && out.presentation.get(index) != null && out.presentation.get(index - 1) != null
                            && java.util.Objects.equals(out.presentation.get(index).groupId(), out.presentation.get(index - 1).groupId());
                    out.searchTables.put(index, (index == first ? marker : sameGroup ? "" : "    ") + markdown.replace("\n", "\n    "));
                }
                continue;
            }
            out.add(new PdfLayoutExtractor.Block("note", label + " " + id + (author.isBlank() ? "" : " · " + author), 0));
            walk(note, out, 0, "note");
        }
        out.currentPart = previousPart;
    }

    private static String attributeNs(Node node, String namespace, String name) {
        return node instanceof Element element ? element.getAttributeNS(namespace, name) : "";
    }

    private void readStyles(Node node, Parsed out) {
        for (Node style : directWords(node, "style")) {
            String id = attribute(style, "styleId");
            out.styleNames.put(id, attribute(wordChild(style, "name"), "val"));
            String level = attribute(wordChild(wordChild(style, "pPr"), "outlineLvl"), "val");
            if (!level.isBlank()) out.outlineLevels.put(id, level);
        }
    }

    private static Node wordChild(Node node, String name) {
        if (node == null) return null;
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && W.equals(n.getNamespaceURI()) && local(n).equals(name)) return n;
        return null;
    }
    private static Node firstElement(Node node, String name) {
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && local(n).equals(name)) return n;
        return null;
    }
    private static List<Node> directWords(Node node, String name) {
        List<Node> result = new ArrayList<>();
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && W.equals(n.getNamespaceURI()) && local(n).equals(name)) result.add(n);
        return result;
    }
    /** Content controls can wrap a row or cell; stop at the requested structure to avoid nested-table duplication. */
    private static List<Node> wrappedWords(Node node, String name, int depth) throws IOException {
        checkDepth(depth);
        List<Node> result = new ArrayList<>();
        if (node != null) for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            String local = local(n);
            if (W.equals(n.getNamespaceURI()) && local.equals(name)) result.add(n);
            else if (!(W.equals(n.getNamespaceURI()) && Set.of("tbl", "p", "tc", "tr").contains(local)))
                result.addAll(wrappedWords(n, name, depth + 1));
        }
        return result;
    }
    private static String attribute(Node node, String name) { return node instanceof Element e ? e.getAttributeNS(W, name) : ""; }
    private static void checkDepth(int depth) throws IOException { if (depth > MAX_DEPTH) throw new IOException("Word 内容嵌套过深，未抽取正文"); }

    private static final class Inline {
        final Map<String, ImageRef> images = new LinkedHashMap<>();
        final Map<String, Markup> markups = new LinkedHashMap<>();
        final ArrayDeque<Field> fields = new ArrayDeque<>();
        Node rootParagraph;
        void image(ImageRef image) {
            String marker = "\u0001image" + images.size() + "\u0002";
            images.put(marker, image); text.append(marker);
        }
        void markup(String markdown, String visible) throws IOException {
            String marker = "\u0005markup" + markups.size() + "\u0006";
            markups.put(marker, new Markup(markdown, visible)); append(marker, false);
        }
        final StringBuilder text = new StringBuilder();
        final StringBuilder prose = new StringBuilder();
        final List<OmmlLatexConverter.Result> formulas = new ArrayList<>();
        void append(String value, boolean ordinary) throws IOException {
            if ((long) text.length() + value.length() > MAX_CHARS) throw new IOException("Word 段落或表格超过抽取长度上限");
            text.append(value); if (ordinary) prose.append(value);
        }
        String status() {
            if (formulas.stream().anyMatch(f -> f.status().equals("unavailable"))) return "unavailable";
            return formulas.stream().anyMatch(f -> f.status().equals("partial")) ? "partial" : "restored";
        }
        String message() {
            Set<String> messages = new LinkedHashSet<>();
            for (var formula : formulas) if (formula.message() != null) messages.add(formula.message());
            return messages.isEmpty() ? null : String.join("；", messages);
        }
    }
    private record Markup(String markdown, String visible) { }
    private static final class Field {
        final StringBuilder instruction = new StringBuilder(); boolean separated; String destination = "";
    }
    private static final class Parsed {
        final List<PdfLayoutExtractor.Block> blocks = new ArrayList<>();
        final boolean forSearch;
        final Map<Integer, DocumentIngestionService.Presentation> presentation = new LinkedHashMap<>();
        final Map<String, Map<String, String>> links = new LinkedHashMap<>();
        int paragraphs;
        final Map<Integer, String> searchTables = new LinkedHashMap<>();
        final OmmlLatexConverter converter = new OmmlLatexConverter();
        final Map<String, String> styleNames = new LinkedHashMap<>(), outlineLevels = new LinkedHashMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        long expandedBytes;
        int chars;
        String currentPart = "word/document.xml";
        Parsed(boolean forSearch) { this.forSearch = forSearch; }
        void add(PdfLayoutExtractor.Block block) throws IOException {
            int length = Math.max(block.text().length(), block.latex() == null ? 0 : block.latex().length());
            if ((long) chars + length > MAX_CHARS || blocks.size() >= 20_000) throw new IOException("Word 正文超过抽取长度上限");
            blocks.add(block); chars += length;
        }
    }
}
