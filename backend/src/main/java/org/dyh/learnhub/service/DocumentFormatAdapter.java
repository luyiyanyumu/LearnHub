package org.dyh.learnhub.service;

import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hssf.usermodel.HSSFPatriarch;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Local format adapters only. No document can select a model, network endpoint, or output filesystem path. */
public final class DocumentFormatAdapter {
    private DocumentFormatAdapter() { }
    private static final int MAX_BLOCKS = 20000, MAX_RAW_CHARS = 800000, MAX_CELLS = 100000;
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("py", "python"), Map.entry("js", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("cjs", "javascript"), Map.entry("ts", "typescript"), Map.entry("vue", "vue"), Map.entry("jsx", "jsx"),
            Map.entry("tsx", "tsx"), Map.entry("json", "json"), Map.entry("jsonc", "jsonc"), Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"), Map.entry("html", "html"), Map.entry("htm", "html"), Map.entry("xml", "xml"),
            Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("less", "less"), Map.entry("sql", "sql"),
            Map.entry("go", "go"), Map.entry("rs", "rust"), Map.entry("rb", "ruby"), Map.entry("php", "php"),
            Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("cpp", "cpp"), Map.entry("hpp", "cpp"),
            Map.entry("cs", "csharp"), Map.entry("kt", "kotlin"), Map.entry("swift", "swift"), Map.entry("sh", "bash"),
            Map.entry("bash", "bash"), Map.entry("zsh", "zsh"), Map.entry("ps1", "powershell"), Map.entry("bat", "bat"),
            Map.entry("cmd", "bat"), Map.entry("dockerfile", "dockerfile"), Map.entry("gradle", "groovy"), Map.entry("groovy", "groovy"),
            Map.entry("lua", "lua"), Map.entry("r", "r"), Map.entry("m", "matlab"), Map.entry("pl", "perl"),
            Map.entry("scala", "scala"), Map.entry("dart", "dart"), Map.entry("tex", "latex"), Map.entry("toml", "toml"),
            Map.entry("ini", "ini"), Map.entry("properties", "properties"), Map.entry("conf", "text"), Map.entry("env", "dotenv"));

    public static boolean structured(String ext) { return Set.of("docx", "docm", "xlsx", "xlsm", "xls", "pptx", "pptm", "ppt").contains(ext); }

    public static DocumentIngestionService.Extraction extract(Path path, String ext) throws IOException {
        if (Files.size(path) > 64L * 1024 * 1024) throw new IOException("文档超过 64 MiB 转换上限，原件已保留");
        if (Set.of("docx", "docm").contains(ext)) return new WordLayoutExtractor().extractForIngestion(path);
        if (Set.of("xlsx", "xlsm", "xls").contains(ext)) return spreadsheet(path);
        if (Set.of("pptx", "pptm", "ppt").contains(ext)) return slides(path, ext);
        throw new IOException("该格式没有结构转换器");
    }

    public static DocumentIngestionService.Extraction fromText(Path path, String ext, String text, boolean truncated) throws IOException {
        if (text.length() > MAX_RAW_CHARS) throw new IOException("文本文档超过 80 万字符转换上限，原件已保留");
        String content = text.replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "").replace("\u0000", "");
        List<DocumentIngestionService.Block> blocks = new ArrayList<>();
        if (Set.of("md", "markdown").contains(ext)) {
            blocks.add(block(1, 1, "markdown", content, 0, DocumentMarkdownWriter.safeMarkdown(content), true, null));
        } else if (LANGUAGES.containsKey(ext)) {
            blocks.add(block(1, 1, "code", content, 0, DocumentMarkdownWriter.fenced(content, LANGUAGES.get(ext)), true, null));
        } else if (Set.of("csv", "tsv").contains(ext)) {
            blocks.add(block(1, 1, "table", content, 0, WordLayoutExtractor.markdownTable(delimited(content, ext.equals("csv") ? ',' : '\t')), true, null));
        } else {
            for (String paragraph : content.split("\n[ \t]*\n", -1)) if (!paragraph.isBlank())
                blocks.add(block(1, blocks.size() + 1, "paragraph", paragraph, 0, null, true, null));
        }
        List<String> warnings = truncated ? List.of("文本输入超过读取上限，仅转换已读取部分；原件完整保留") : List.of();
        return wrap(path, List.of(new DocumentIngestionService.Page(1, 0, 0, "none", "text", blocks)), warnings, truncated);
    }

    public static DocumentIngestionService.Extraction fromLegacy(Path path, String ext, DocumentTextService.Extracted extracted) {
        if (!"ok".equals(extracted.status())) return DocumentMarkdownWriter.render(DocumentIngestionService.fromLegacy(path, extracted));
        try { return fromText(path, ext, extracted.text(), extracted.chars() > extracted.text().length()); }
        catch (IOException invalid) { return failed(path, invalid); }
    }

    static DocumentIngestionService.Extraction failed(Path path, Exception error) {
        return DocumentMarkdownWriter.render(DocumentIngestionService.fromLegacy(path,
                new DocumentTextService.Extracted("failed", null, error.getMessage() == null ? "文档转换失败，原件已保留" : error.getMessage(), 0)));
    }

    static DocumentIngestionService.Extraction wrap(Path path, List<DocumentIngestionService.Page> pages,
                                                    List<String> warnings, boolean truncated) throws IOException {
        int blocks = 0, chars = 0;
        for (var page : pages) for (var block : page.blocks()) {
            chars += block.rawText().length();
            if (++blocks > MAX_BLOCKS || chars > MAX_RAW_CHARS) throw new IOException("文档超过 20000 块/80 万原始字符转换上限，原件已保留");
        }
        String hash = DocumentIngestionService.fromLegacy(path, new DocumentTextService.Extracted("empty", "", null, 0)).sourceSha256();
        if (hash.isBlank()) throw new IOException("原件哈希读取失败，未提交无版本的 Markdown 结果");
        var report = new DocumentIngestionService.QualityReport(pages.size(), pages.size(), 0, 0, null,
                List.copyOf(warnings), truncated, truncated || !warnings.isEmpty());
        return DocumentMarkdownWriter.render(new DocumentIngestionService.Extraction("ok", "", 0, null, hash,
                DocumentIngestionService.PIPELINE_VERSION, List.copyOf(pages), report));
    }

    static DocumentIngestionService.Block block(int page, int number, String type, String text, int level,
                                               String markdown, boolean indexed, String src) {
        return new DocumentIngestionService.Block("p" + page + "-b" + number, type, text, text, page, null, 0, 0,
                null, indexed ? "accepted" : "non_text", indexed, -1, -1, level, src, null, null, null,
                markdown, -1, -1);
    }

    private static DocumentIngestionService.Extraction spreadsheet(Path path) throws IOException {
        List<DocumentIngestionService.Page> pages = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(path.toFile())) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int cells = 0;
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                var sheet = workbook.getSheetAt(s);
                int columns = 0;
                for (Row row : sheet) columns = Math.max(columns, row.getLastCellNum());
                if (columns > 1000 || (long) (sheet.getLastRowNum() + 1) * columns > MAX_CELLS - cells)
                    throw new IOException("工作表超过 10 万单元格转换上限，原件已保留");
                List<List<String>> rows = new ArrayList<>();
                for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum() && columns > 0; r++) {
                    Row row = sheet.getRow(r); List<String> values = new ArrayList<>();
                    for (int c = 0; c < columns; c++) {
                        var cell = row == null ? null : row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        values.add(cell == null ? "" : cell.getCellType() == org.apache.poi.ss.usermodel.CellType.FORMULA
                                ? "=" + cell.getCellFormula() : formatter.formatCellValue(cell));
                        cells++;
                    }
                    rows.add(values);
                }
                List<DocumentIngestionService.Block> blocks = new ArrayList<>();
                blocks.add(block(s + 1, 1, "heading", sheet.getSheetName(), 1, null, true, null));
                if (!rows.isEmpty()) blocks.add(block(s + 1, 2, "table", String.join("\n", rows.stream().map(row -> String.join("\t", row)).toList()),
                        0, WordLayoutExtractor.markdownTable(rows), true, null));
                Object drawing = sheet.getDrawingPatriarch();
                List<?> shapes = drawing instanceof XSSFDrawing xssf ? xssf.getShapes()
                        : drawing instanceof HSSFPatriarch hssf ? hssf.getChildren() : List.of();
                for (int shape = 0; shape < shapes.size(); shape++)
                    if (shapes.get(shape) instanceof XSSFPicture || shapes.get(shape) instanceof HSSFPicture)
                        blocks.add(block(s + 1, blocks.size() + 1, "figure", "", 0, null, false,
                                "xlsx:" + s + "#" + shape));
                pages.add(new DocumentIngestionService.Page(s + 1, 0, 0, "sheet", "text", blocks));
            }
        } catch (Exception invalid) { if (invalid instanceof IOException io) throw io; throw new IOException("工作表无法转换，原件已保留", invalid); }
        return wrap(path, pages, List.of(), false);
    }

    private static DocumentIngestionService.Extraction slides(Path path, String ext) throws IOException {
        List<DocumentIngestionService.Page> pages = new ArrayList<>(); List<String> warnings = new ArrayList<>();
        if (ext.equals("ppt")) {
            try (HSLFSlideShow show = new HSLFSlideShow(Files.newInputStream(path))) {
                for (var slide : show.getSlides()) pages.add(slide(slide, pages.size() + 1, warnings));
            }
        } else {
            try (XMLSlideShow show = new XMLSlideShow(Files.newInputStream(path))) {
                for (var slide : show.getSlides()) pages.add(slide(slide, pages.size() + 1, warnings));
            }
        }
        return wrap(path, pages, warnings, false);
    }

    private static DocumentIngestionService.Page slide(Slide<?, ?> slide, int page, List<String> warnings) throws IOException {
        List<DocumentIngestionService.Block> blocks = new ArrayList<>();
        // This label describes the logical container; it is not text from the source slide.
        blocks.add(block(page, 1, "heading", "幻灯片 " + page, 1, null, false, null));
        List<?> shapes = slide.getShapes();
        for (int shapeIndex = 0; shapeIndex < shapes.size(); shapeIndex++) {
            Object shape = shapes.get(shapeIndex);
            List<List<String>> rows = new ArrayList<>();
            if (shape instanceof XSLFTable table) {
                for (var row : table.getRows()) rows.add(row.getCells().stream().map(cell -> cell.getText()).toList());
            } else if (shape instanceof HSLFTable table) {
                for (int r = 0; r < table.getNumberOfRows(); r++) {
                    List<String> values = new ArrayList<>();
                    for (int c = 0; c < table.getNumberOfColumns(); c++) values.add(table.getCell(r, c) == null ? "" : table.getCell(r, c).getText());
                    rows.add(values);
                }
            }
            if (!rows.isEmpty()) blocks.add(block(page, blocks.size() + 1, "table", String.join("\n", rows.stream().map(row -> String.join("\t", row)).toList()),
                    0, WordLayoutExtractor.markdownTable(rows), true, null));
            else if (shape instanceof TextShape<?, ?> text && text.getText() != null && !text.getText().isBlank()) {
                String value = text.getText().replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n');
                boolean title = text.getPlaceholder() == Placeholder.TITLE || text.getPlaceholder() == Placeholder.CENTERED_TITLE
                        || text.getPlaceholder() == Placeholder.VERTICAL_TEXT_TITLE;
                String markdown = DocumentMarkdownWriter.inlineText(value).replace("\n", "  \n");
                if (title) markdown = "# " + markdown;
                blocks.add(block(page, blocks.size() + 1, title ? "heading" : "paragraph", value,
                        title ? 1 : 0, markdown, true, null));
            }
            else if (shape instanceof XSLFPictureShape || shape instanceof HSLFPictureShape) {
                String reference = "ppt:" + page + "#" + shapeIndex;
                var figure = block(page, blocks.size() + 1, "figure", "", 0, null, false, reference);
                var anchor = ((Shape<?, ?>) shape).getAnchor();
                Double width = cssPixels(anchor == null ? 0 : anchor.getWidth());
                Double height = cssPixels(anchor == null ? 0 : anchor.getHeight());
                blocks.add(new DocumentIngestionService.Block(figure.id(), figure.type(), figure.rawText(), figure.cleanText(),
                        figure.page(), figure.rect(), figure.width(), figure.height(), figure.confidence(), figure.quality(),
                        figure.indexed(), figure.start(), figure.end(), figure.level(), figure.src(), figure.latex(),
                        figure.latexStatus(), figure.latexMessage(), figure.markdown(), figure.markdownStart(), figure.markdownEnd(),
                        new DocumentIngestionService.Presentation(null, "block", width, height, null, reference)));
            }
        }
        return new DocumentIngestionService.Page(page, 0, 0, "slide", "text", blocks);
    }

    private static Double cssPixels(double points) {
        double value = points * 96 / 72;
        return Double.isFinite(value) && value > 0 && value <= 20000 ? value : null;
    }

    private static List<List<String>> delimited(String text, char delimiter) throws IOException {
        List<List<String>> rows = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder field = new StringBuilder();
        boolean quoted = false; int cells = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
            else if (c == '"' && (quoted || field.isEmpty())) quoted = !quoted;
            else if (!quoted && (c == delimiter || c == '\n')) {
                row.add(field.toString()); field.setLength(0);
                if (++cells > MAX_CELLS) throw new IOException("分隔表格超过 10 万单元格上限");
                if (c == '\n') { rows.add(List.copyOf(row)); row.clear(); }
            } else field.append(c);
        }
        if (quoted) throw new IOException("分隔表格包含未闭合引号，原件已保留");
        if (!row.isEmpty() || !field.isEmpty()) { row.add(field.toString()); rows.add(List.copyOf(row)); }
        return rows;
    }
}
