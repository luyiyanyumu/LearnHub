package org.dyh.learnhub.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 资料库的「文本层」：把上传的文档抽成可检索的正文。
 *
 * <h3>为什么这一步是"资料库融进知识库"的前提</h3>
 * 检索、主题 wiki、知识图谱、智能体的自动召回全都建立在**文本**上。
 * 原来 {@code file_info} 只有文件名与大小，所以资料对知识库是不透明的 ——
 * 抽不出正文，后面四层接入全都无从谈起。
 *
 * <h3>抽取策略</h3>
 * <ul>
 *   <li>纯文本类（md/txt/log/json/yml/sql/各种源码）：直接按编码读，零依赖；</li>
 *   <li>PDF：PDFBox 逐页取文（扫描版 PDF 是图片，本来就抽不出字，这里如实标注为空）；</li>
 *   <li>Office：POI 覆盖 docx/doc、xlsx/xls、pptx/ppt；</li>
 *   <li>其它二进制（图片、压缩包…）：标记 unsupported —— 仍可**按文件名与手填说明**参与检索。</li>
 * </ul>
 *
 * <p><b>失败绝不外抛</b>：抽取是"锦上添花"，上传本身必须成功。
 * 出错时把原因写进 {@code text_error} 存库，界面上能看到，也方便排查。
 */
@Slf4j
@Service
public class DocumentTextService {

    /** 存库正文上限（字符）。再长对检索与模型都没意义，只会拖慢一切 */
    public static final int MAX_TEXT_CHARS = 400_000;

    /** 超过这个大小直接不抽：避免上传接口被一个大文件卡住（如实标注 skipped） */
    private static final long MAX_EXTRACT_BYTES = 20L * 1024 * 1024;

    /** PDF 页数上限：几百页的文档抽完对知识库也无用 */
    private static final int MAX_PDF_PAGES = 300;

    public static final String STATUS_OK = "ok";
    public static final String STATUS_EMPTY = "empty";
    public static final String STATUS_UNSUPPORTED = "unsupported";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";

    /** 直接按文本读的扩展名 */
    private static final Set<String> TEXT_EXTS = Set.of(
            "md", "markdown", "txt", "text", "log", "csv", "tsv", "json", "jsonc", "yml", "yaml",
            "xml", "html", "htm", "css", "scss", "less", "sql", "properties", "ini", "conf", "env",
            "toml", "java", "js", "mjs", "cjs", "ts", "vue", "jsx", "tsx", "py", "go", "rs", "rb",
            "php", "c", "h", "cpp", "hpp", "cs", "kt", "swift", "sh", "bash", "zsh", "ps1", "bat",
            "cmd", "dockerfile", "gradle", "groovy", "lua", "r", "m", "pl", "scala", "dart", "tex");

    public record Extracted(String status, String text, String error, int chars) {
    }

    /**
     * 抽取正文。**任何异常都在内部消化**并转成 status=failed。
     *
     * @param path 磁盘上的文件
     * @param ext  小写扩展名（不含点）
     * @param size 文件大小
     */
    public Extracted extract(Path path, String ext, long size) {
        String e = ext == null ? "" : ext.toLowerCase(Locale.ROOT);
        if (size > MAX_EXTRACT_BYTES) {
            return new Extracted(STATUS_SKIPPED, null,
                    "文件超过 " + (MAX_EXTRACT_BYTES / 1024 / 1024) + "MB，未抽取正文（可按文件名与说明检索）", 0);
        }
        if (e.isEmpty()) {
            return new Extracted(STATUS_UNSUPPORTED, null, "没有扩展名，无法判断类型", 0);
        }
        try {
            String text;
            if (TEXT_EXTS.contains(e)) {
                text = readPlainText(path);
            } else {
                text = switch (e) {
                    case "pdf" -> pdf(path);
                    case "docx", "docm" -> wordOoxml(path);
                    case "doc" -> wordLegacy(path);
                    case "xlsx", "xlsm" -> excelOoxml(path);
                    case "xls" -> excelLegacy(path);
                    case "pptx", "pptm" -> pptOoxml(path);
                    case "ppt" -> pptLegacy(path);
                    default -> null;
                };
            }
            if (text == null) {
                return new Extracted(STATUS_UNSUPPORTED, null,
                        "暂不支持抽取该类型（图片/压缩包等），可按文件名与手填说明检索", 0);
            }
            String clean = tidy(text);
            if (clean.isEmpty()) {
                return new Extracted(STATUS_EMPTY, null,
                        "文件里没有可提取的文字（例如扫描版 PDF 或纯图片）", 0);
            }
            int chars = clean.length();
            String stored = chars > MAX_TEXT_CHARS ? clean.substring(0, MAX_TEXT_CHARS) : clean;
            return new Extracted(STATUS_OK, stored, null, chars);
        } catch (Throwable t) {
            // 关键：抽取失败绝不能影响上传本身
            log.warn("文档抽文失败: {} ({}) - {}", path.getFileName(), e, t.toString());
            return new Extracted(STATUS_FAILED, null, brief(t.getMessage() == null ? t.toString() : t.getMessage()), 0);
        }
    }

    // ---------------- 各类格式 ----------------

    /** 纯文本：先按 UTF-8 严格解码，失败再退 GBK（老日志/老 txt 常见） */
    private String readPlainText(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 4 * 1024 * 1024) {
            byte[] cut = new byte[4 * 1024 * 1024];
            System.arraycopy(bytes, 0, cut, 0, cut.length);
            bytes = cut;
        }
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return utf8;
        }
        String gbk = decodeStrict(bytes, Charset.forName("GBK"));
        return gbk != null ? gbk : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 严格解码：编码不对就返回 null（而不是塞一堆 U+FFFD 进知识库） */
    private static String decodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String pdf(Path path) throws IOException {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int pages = Math.min(doc.getNumberOfPages(), MAX_PDF_PAGES);
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            return stripper.getText(doc);
        }
    }

    private String wordOoxml(Path path) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(path));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    private String wordLegacy(Path path) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(Files.newInputStream(path));
             WordExtractor ex = new WordExtractor(doc)) {
            return ex.getText();
        }
    }

    private String excelOoxml(Path path) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(path))) {
            return sheetText(wb);
        }
    }

    private String excelLegacy(Path path) throws IOException {
        try (HSSFWorkbook wb = new HSSFWorkbook(Files.newInputStream(path))) {
            return sheetText(wb);
        }
    }

    /** 表格取文：`表名 | 单元格 | 单元格` 一行一行来 —— 比 POI 默认输出更适合检索与模型阅读 */
    private String sheetText(Workbook wb) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < wb.getNumberOfSheets(); s++) {
            Sheet sheet = wb.getSheetAt(s);
            sb.append("## ").append(sheet.getSheetName()).append('\n');
            for (Row row : sheet) {
                StringBuilder line = new StringBuilder();
                for (Cell cell : row) {
                    String v = cellText(cell);
                    if (!v.isEmpty()) {
                        if (line.length() > 0) {
                            line.append(" | ");
                        }
                        line.append(v);
                    }
                }
                if (line.length() > 0) {
                    sb.append(line).append('\n');
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String cellText(Cell cell) {
        try {
            return switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue().trim();
                case NUMERIC -> org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)
                        ? String.valueOf(cell.getLocalDateTimeCellValue())
                        : trimNumber(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> "";
            };
        } catch (Exception e) {
            return "";
        }
    }

    /** 3.0 不要写成 "3.0"（Excel 里数字列的可读性） */
    private static String trimNumber(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /** pptx：POI 5.x 用通用的 SlideShowExtractor（旧的 XSLFPowerPointExtractor 已移除） */
    private String pptOoxml(Path path) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(Files.newInputStream(path))) {
            return new SlideShowExtractor<>(ppt).getText();
        }
    }

    /** ppt（legacy 二进制格式）：同一个 SlideShowExtractor 也能吃 HSLF */
    private String pptLegacy(Path path) throws IOException {
        try (HSLFSlideShow ppt = new HSLFSlideShow(Files.newInputStream(path))) {
            return new SlideShowExtractor<>(ppt).getText();
        }
    }

    /** 归一化：CRLF → LF、去掉零宽字符、压掉三连以上空行、去掉行尾空白，并修掉双重编码乱码 */
    static String tidy(String s) {
        if (s == null) {
            return "";
        }
        return repairMojibake(s.replace("\r\n", "\n").replace('\r', '\n')
                .replace("\uFEFF", "").replace("\u0000", "")
                .replaceAll("(?m)[ \t]+$", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim());
    }

    /**
     * 修掉"UTF-8 字节被当成 Latin-1 读进来"的乱码。
     *
     * <p>PDF 里 ToUnicode 映射不全的字体（论文中的 – ’ ∗ 这类符号最常见）会被 PDFBox 映射成
     * U+0080~U+00FF 的**原始字节值**，于是正文里出现 "â€“" 这种双重编码 —— 界面上看是乱码，
     * 检索时也永远匹配不上。做法是把连续的 0x80~0xFF 片段当成字节重新按 UTF-8 解一次：
     * 解得开就替换，解不开（真正的 Latin-1 文本，如 "café"）原样保留。
     */
    public static String repairMojibake(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c < 0x80 || c > 0xFF) {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < s.length() && s.charAt(j) >= 0x80 && s.charAt(j) <= 0xFF) {
                j++;
            }
            String run = s.substring(i, j);
            String fixed = decodeLatin1AsUtf8(run);
            out.append(fixed != null ? fixed : run);
            i = j;
        }
        return out.toString();
    }

    /** 把一段 U+0080~U+00FF 当作原始字节按 UTF-8 解；解不出或有控制字符就返回 null（保持原样） */
    private static String decodeLatin1AsUtf8(String run) {
        if (run.length() < 2) {
            return null;
        }
        byte[] bytes = new byte[run.length()];
        for (int i = 0; i < run.length(); i++) {
            bytes[i] = (byte) run.charAt(i);
        }
        String decoded = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (decoded == null) {
            return null;
        }
        for (char c : decoded.toCharArray()) {
            if (c < 0x20 || (c >= 0x7F && c <= 0xA0)) {
                return null;   // 解出来是控制字符：说明这本来就不是 UTF-8 字节序列
            }
        }
        return decoded;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 240 ? t.substring(0, 240) + "…" : t;
    }
}
