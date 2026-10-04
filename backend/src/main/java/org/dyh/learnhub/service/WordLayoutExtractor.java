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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Pattern HEADING = Pattern.compile("(?i)(?:heading|标题)[ _-]?([1-6])");

    public PdfLayoutExtractor.Layout extract(Path path) throws IOException {
        Parsed parsed = parse(path);
        List<PdfLayoutExtractor.PageLayout> pages = parsed.blocks.isEmpty() ? List.of()
                : List.of(new PdfLayoutExtractor.PageLayout(1, 1, List.copyOf(parsed.blocks)));
        return new PdfLayoutExtractor.Layout(parsed.blocks.isEmpty() ? "empty" : "ok",
                parsed.warnings.isEmpty() ? null : String.join("；", parsed.warnings), pages,
                parsed.chars, pages.size());
    }

    /** Search text uses the same equation conversion as the reading view. */
    public String plainText(Path path) throws IOException {
        Parsed parsed = parse(path);
        StringBuilder text = new StringBuilder();
        for (var block : parsed.blocks) {
            if (block.type().equals("formula") && block.latex() != null) text.append("$$").append(block.latex()).append("$$");
            else text.append(block.text());
            if (block.latexStatus() != null && !block.latexStatus().equals("restored"))
                text.append(" [公式未完整还原：").append(block.latexMessage()).append(']');
            text.append('\n');
        }
        return DocumentTextService.tidy(text.toString());
    }

    private Parsed parse(Path path) throws IOException {
        if (Files.size(path) > 20L * 1024 * 1024) throw new IOException("Word 文件超过 20 MiB，未抽取正文");
        Parsed out = new Parsed();
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
            else if (!(W.equals(n.getNamespaceURI()) && local(n).endsWith("Pr"))) walk(n, out, depth + 1, override);
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
        Inline inline = new Inline();
        inline(node, inline, out, depth);
        String value = DocumentTextService.tidy(inline.text.toString());
        if (value.isEmpty()) return;
        if (inline.formulas.size() == 1 && inline.prose.toString().isBlank() && override == null) {
            var math = inline.formulas.getFirst();
            out.add(mathBlock(math, "formula", 0));
        } else {
            out.add(new PdfLayoutExtractor.Block(type, value, heading, null, null, null, null,
                    inline.formulas.isEmpty() ? null : inline.status(), inline.message()));
        }
    }

    private void inline(Node node, Inline target, Parsed out, int depth) throws IOException {
        checkDepth(depth);
        if (node.getNodeType() != Node.ELEMENT_NODE) return;
        String name = local(node);
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
        if (W.equals(node.getNamespaceURI()) && name.equals("p") && depth > 1) target.append("\n", true);
    }

    private void table(Node node, Parsed out, int depth, String override) throws IOException {
        checkDepth(depth);
        Inline contents = new Inline();
        for (Node row : wrappedWords(node, "tr", depth)) {
            boolean first = true;
            for (Node cell : wrappedWords(row, "tc", depth + 1)) {
                if (!first) contents.append("\t", true);
                Inline value = new Inline(); inline(cell, value, out, depth + 1);
                contents.append(value.text.toString().strip(), true);
                contents.formulas.addAll(value.formulas);
                first = false;
            }
            contents.append("\n", true);
        }
        String value = DocumentTextService.tidy(contents.text.toString());
        if (!value.isEmpty()) out.add(new PdfLayoutExtractor.Block(override == null ? "table" : override, value, 0,
                null, null, null, null, contents.formulas.isEmpty() ? null : contents.status(), contents.message()));
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
        for (Node note : directWords(document.getDocumentElement(), itemName)) {
            String id = attribute(note, "id");
            if (!itemName.equals("comment") && id.matches("-?\\d+") && (id.startsWith("-") || id.matches("0+"))) continue;
            String author = attribute(note, "author");
            out.add(new PdfLayoutExtractor.Block("note", label + " " + id + (author.isBlank() ? "" : " · " + author), 0));
            walk(note, out, 0, "note");
        }
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
    private static final class Parsed {
        final List<PdfLayoutExtractor.Block> blocks = new ArrayList<>();
        final OmmlLatexConverter converter = new OmmlLatexConverter();
        final Map<String, String> styleNames = new LinkedHashMap<>(), outlineLevels = new LinkedHashMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        long expandedBytes;
        int chars;
        void add(PdfLayoutExtractor.Block block) throws IOException {
            int length = Math.max(block.text().length(), block.latex() == null ? 0 : block.latex().length());
            if ((long) chars + length > MAX_CHARS || blocks.size() >= 20_000) throw new IOException("Word 正文超过抽取长度上限");
            blocks.add(block); chars += length;
        }
    }
}
