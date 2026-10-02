package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.dyh.learnhub.common.KnowledgeChangedEvent;
import org.dyh.learnhub.entity.FileInfo;
import org.dyh.learnhub.mapper.CategoryMapper;
import org.dyh.learnhub.mapper.FileInfoMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 资料的存储与正文抽取。
 *
 * <p>职责：磁盘落盘 + 元数据入库 + **上传后立刻抽正文**（抽出来的正文是检索层的输入，
 * 抽不出时如实记录状态与原因，而不是留下一个空壳）。内容变更通过
 * {@link KnowledgeChangedEvent} 通知增量索引与 wiki 更新。
 *
 * <p>⚠️ 本文件曾被一次脚本误写成 1 个字符（PowerShell 把行数组当成了字符串），
 * 之后按编译产物 `javap` 反查的方法清单逐一对齐重建 —— 改动涉及"批量重写整个文件"时，
 * 必须改用 edit 工具或先备份，不能再用行数组拼接那种写法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final FileInfoMapper fileInfoMapper;
    private final CategoryMapper categoryMapper;
    private final DocumentTextService documentTextService;
    private final PdfLayoutExtractor pdfLayoutExtractor;
    private final ApplicationEventPublisher events;
    private final LearningActivityService learningActivity;

    /** 文件存放目录：后端工作目录下的 uploads/ */
    private Path storageDir() {
        Path dir = Paths.get(System.getProperty("user.dir"), "uploads");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + dir, e);
        }
        return dir;
    }

    /** 资料列表：支持分类 / 关键词过滤 */
    public List<Map<String, Object>> list(Long categoryId, String kw) {
        LambdaQueryWrapper<FileInfo> wrapper = Wrappers.<FileInfo>lambdaQuery()
                .orderByDesc(FileInfo::getCreatedAt);
        if (categoryId != null && categoryId > 0) {
            wrapper.eq(FileInfo::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(kw)) {
            wrapper.like(FileInfo::getOriginName, kw.trim());
        }
        return fileInfoMapper.selectList(wrapper).stream().map(this::toMap).collect(Collectors.toList());
    }

    /** 上传文件：保存到磁盘 + 写入元数据 + **立刻抽正文**（抽不到也记录原因） */
    @Transactional
    public FileInfo upload(MultipartFile file, Long categoryId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        String originName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename());
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取上传内容失败: " + e.getMessage(), e);
        }
        return uploadBytes(originName, bytes, categoryId);
    }

    /**
     * 用**字节数组**入库（智能体的「把论文存进资料库」与 MCP 桥接走这条路）。
     *
     * <p>为什么不复用 MultipartFile：那份接口要求调用方造一个假的上传对象
     * （Spring 的 MockMultipartFile 在 test 作用域里，生产代码拿不到）。
     * 抽成字节数组后，网页上传、智能体下载入库、将来任何"内容已经在内存里"的场景共用同一条落盘/抽文/通知链路，
     * 也就不会出现"某条路径忘了抽正文"这种半截实现。
     */
    @Transactional
    public FileInfo uploadBytes(String originName, byte[] bytes, Long categoryId) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        String name = StringUtils.cleanPath(StringUtils.hasText(originName) ? originName.trim() : "unnamed");
        String ext = extOf(name);
        String storeName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);

        Path target = storageDir().resolve(storeName);
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("文件保存失败: " + e.getMessage(), e);
        }

        FileInfo info = new FileInfo();
        info.setOriginName(name);
        info.setStoreName(storeName);
        info.setSize((long) bytes.length);
        info.setExt(ext);
        info.setCategoryId(categoryId);
        info.setTextStatus("pending");
        fileInfoMapper.insert(info);
        learningActivity.record("file", info.getId());
        // 抽取放在入库之后：即使抽取失败/超时，资料本身也已经存在（可重试），不会出现"文件丢了但记录在"
        extractInto(info, target);
        publishChanged(categoryId);
        log.info("文件入库成功: {} ({} bytes) -> {}，正文 {}", name, bytes.length, storeName, info.getTextStatus());
        return info;
    }

    /** 内容变更通知：增量索引 / wiki 自动更新靠它（发的是**分类 id**，因为它们决定图谱归属） */
    private void publishChanged(Long... categoryIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : categoryIds) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        events.publishEvent(new KnowledgeChangedEvent(ids, "file"));
    }

    /** 启动时补抽：升级前上传、或当时抽取失败/仍在 pending 的资料 */
    public void backfillPendingText() {
        List<FileInfo> todo = fileInfoMapper.selectList(Wrappers.<FileInfo>lambdaQuery()
                .and(w -> w.isNull(FileInfo::getTextStatus)
                        .or().in(FileInfo::getTextStatus, List.of("pending", "failed")))
                .last("LIMIT 200"));
        if (todo.isEmpty()) {
            return;
        }
        int done = 0;
        for (FileInfo info : todo) {
            Path path = storageDir().resolve(info.getStoreName());
            if (!Files.exists(path)) {
                continue;
            }
            extractInto(info, path);
            done++;
        }
        log.info("启动补抽正文：{} 条", done);
        if (done > 0) {
            publishChanged(todo.stream().map(FileInfo::getCategoryId).toArray(Long[]::new));
        }
    }

    /**
     * 抽取正文并写回元数据。
     *
     * <p>无论成功失败都写回状态：{@code ok / empty / unsupported / skipped / failed} + 原因。
     * 这样界面上能解释"为什么正文是空的"，而不是留一片空白让用户以为坏了。
     */
    private String extractInto(FileInfo info, Path path) {
        DocumentTextService.Extracted r = documentTextService.extract(path, info.getExt(), info.getSize());
        info.setTextStatus(r.status());
        info.setTextContent(r.text());
        info.setTextChars(r.chars());
        info.setTextError(r.error());
        info.setExtractedAt(LocalDateTime.now());
        fileInfoMapper.updateById(info);
        return r.status();
    }

    /** 手动重新抽取正文 */
    public Map<String, Object> reextract(Long id) {
        FileInfo info = require(id);
        Path path = storageDir().resolve(info.getStoreName());
        if (!Files.exists(path)) {
            throw new IllegalStateException("文件已丢失: " + info.getStoreName());
        }
        extractInto(info, path);
        publishChanged(info.getCategoryId());
        return detailMap(id);
    }

    /** 更新手填说明（抽不出正文的资料靠它进检索） */
    public Map<String, Object> updateSummary(Long id, String summary) {
        FileInfo info = require(id);
        info.setSummary(summary == null ? null : summary.trim());
        fileInfoMapper.updateById(info);
        publishChanged(info.getCategoryId());
        return detailMap(id);
    }

    /** 换分类（资料按分类进知识图谱） */
    public Map<String, Object> updateCategory(Long id, Long categoryId) {
        FileInfo info = require(id);
        info.setCategoryId(categoryId);
        fileInfoMapper.updateById(info);
        publishChanged(categoryId);
        return detailMap(id);
    }

    private FileInfo require(Long id) {
        FileInfo info = id == null ? null : fileInfoMapper.selectById(id);
        if (info == null) {
            throw new IllegalArgumentException("资料不存在: " + id);
        }
        return info;
    }

    /** 资料实体（内部用） */
    public FileInfo detail(Long id) {
        return require(id);
    }

    /** 资料详情（含分类名、是否有正文、抽取状态）—— 正文不在这里返回，见 textOf */
    public Map<String, Object> detailMap(Long id) {
        FileInfo info = require(id);
        Map<String, Object> m = new LinkedHashMap<>(toMap(info));
        m.put("categoryName", categoryNameOf(info.getCategoryId()));
        m.put("hasText", StringUtils.hasText(info.getTextContent()));
        return m;
    }

    /**
     * 抽取出来的正文（阅读器用）：正文 + 状态 + 字数。
     * <p>单独一个接口而不是塞进 detail：正文动辄十几万字（实测一份 PDF 118,747 字），
     * 列表与详情页根本用不到。抽不出时把原因一并返回，界面才能解释"为什么这里是空的"。
     */
    public Map<String, Object> textOf(Long id) {
        FileInfo info = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", info.getId());
        m.put("originName", info.getOriginName());
        m.put("ext", info.getExt());
        m.put("text", info.getTextContent() == null ? "" : info.getTextContent());
        m.put("chars", info.getTextContent() == null ? 0 : info.getTextContent().length());
        m.put("textStatus", info.getTextStatus());
        m.put("textError", info.getTextError());
        return m;
    }

    /**
     * 排版还原后的正文（阅读器「抽取正文」用）。
     *
     * <p>与 {@link #textOf} 的分工：textOf 给的是**检索层实际用的那份纯文本**（一行一段、两栏交错），
     * 这里给的是**按坐标重建过版面的结构化正文**（标题/作者/章节/段落/列表/脚注，按页返回），
     * 界面照原文档排版，"抽取正文"才能当原文读。非 PDF 一律返回 unsupported，前端继续用分段正文。
     */
    public Map<String, Object> layoutOf(Long id) {
        FileInfo info = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", info.getId());
        m.put("ext", info.getExt() == null ? "" : info.getExt());
        m.put("textStatus", info.getTextStatus() == null ? "" : info.getTextStatus());
        String ext = info.getExt() == null ? "" : info.getExt().toLowerCase(Locale.ROOT);
        if (!"pdf".equals(ext)) {
            m.put("status", "unsupported");
            m.put("error", "排版还原只针对 PDF，其它格式请用分段正文");
            m.put("pages", List.of());
            m.put("chars", 0);
            m.put("pageCount", 0);
            return m;
        }
        Path path = storageDir().resolve(info.getStoreName());
        if (!Files.exists(path)) {
            m.put("status", "missing");
            m.put("error", "文件已丢失: " + info.getStoreName());
            m.put("pages", List.of());
            m.put("chars", 0);
            m.put("pageCount", 0);
            return m;
        }
        PdfLayoutExtractor.Layout layout = pdfLayoutExtractor.extract(path);
        m.put("status", layout.status());
        m.put("error", layout.error());
        m.put("pages", layout.pages());
        m.put("chars", layout.chars());
        m.put("pageCount", layout.pageCount());
        return m;
    }

    /** 渲染插图的分辨率：150 够看清图里的字，单页位图也就 8MB 左右，不至于把内存吃掉 */
    private static final float FIGURE_DPI = 150f;

    /**
     * 「抽取正文」里的插图：把 PDF 那一页渲染出来，**只裁那块图**，返回 PNG 字节。
     *
     * <p>为什么裁而不是整页截：论文的图与正文是排在一起的，整页截图会把两栏正文也塞进图里。
     * 位置来自 {@link PdfLayoutExtractor#figureRect}（单位正方形经 CTM 变换后的包围盒），
     * 所以这里只要按 {@code dpi/72} 缩放裁一刀。
     *
     * <p>裁出来的图缓存到 {@code uploads/.derived/<fileId>/p<页>-<序号>.png}：
     * 翻页来回滚动时不会再渲染一遍（渲染一页 150dpi 要几十毫秒，滚动时很显眼）。
     */
    public byte[] pageImage(Long id, int pageNo, int idx) {
        int[] rect = rectOf(id, pageNo, idx);
        return cropPng(id, pageNo, "f" + idx, rect);
    }

    /**
     * 按**任意区域**裁一页（pt，左上角原点、y 向下）：公式块用这个。
     *
     * <p>为什么公式要走"裁原图"：PDF 里没有公式对象，符号还是散落的字形（上下标字号都不一样），
     * 抽出来的文本必然是碎片（见 docs/pdf-layout-design.md §5.3）。原图里的排版与上下标是准的，
     * 所以 {@code formula} 块把原 PDF 那块渲染出来贴上去 —— 与插图同一套做法、同一套缓存。
     */
    public byte[] pageImageRect(Long id, int pageNo, double x0, double y0, double x1, double y1) {
        if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(x1) || !Double.isFinite(y1)
                || x1 <= x0 || y1 <= y0 || pageNo < 1) {
            throw new IllegalArgumentException("裁剪区域不合法");
        }
        double s = FIGURE_DPI / 72.0;
        int left = (int) Math.floor(x0 * s), top = (int) Math.floor(y0 * s);
        int right = (int) Math.ceil(x1 * s), bottom = (int) Math.ceil(y1 * s);
        int[] rect = {left, top, right - left, bottom - top};
        // 使用实际像素边界，避免两个不同小数坐标共享缓存；v2 不复用旧的裁剪图。
        String key = "v2-r" + left + "_" + top + "_" + right + "_" + bottom;
        return cropPng(id, pageNo, key, rect);
    }

    /** 插图：位置来自 {@link PdfLayoutExtractor#figureRect} */
    private int[] rectOf(Long id, int pageNo, int idx) {
        FileInfo info = require(id);
        Path src = storageDir().resolve(info.getStoreName());
        if (!Files.exists(src)) {
            throw new IllegalStateException("文件已丢失: " + info.getStoreName());
        }
        int[] rect = pdfLayoutExtractor.figureRect(src, pageNo, idx, FIGURE_DPI);
        if (rect == null) {
            throw new IllegalArgumentException("第 " + pageNo + " 页没有第 " + idx + " 张插图");
        }
        return rect;
    }

    /**
     * 渲染该页 → 按 {@code rect}（px）裁一刀 → PNG（带缓存）。
     * <p>缓存到 {@code uploads/.derived/<fileId>/p<页>-<key>.png}：公式与插图共用这一套，
     * 翻页来回滚动时不会再渲染一遍（渲染一页 150dpi 要几十毫秒，滚动时很显眼）。
     */
    private byte[] cropPng(Long id, int pageNo, String key, int[] rect) {
        FileInfo info = require(id);
        String ext = info.getExt() == null ? "" : info.getExt().toLowerCase(Locale.ROOT);
        if (!"pdf".equals(ext)) {
            throw new IllegalArgumentException("只有 PDF 才能按区域裁剪");
        }
        Path src = storageDir().resolve(info.getStoreName());
        if (!Files.exists(src)) {
            throw new IllegalStateException("文件已丢失: " + info.getStoreName());
        }
        Path cache = storageDir().resolve(".derived").resolve(String.valueOf(id))
                .resolve("p" + pageNo + "-" + key + ".png");
        try {
            if (Files.exists(cache)) {
                return Files.readAllBytes(cache);
            }
            byte[] png;
            try (PDDocument doc = Loader.loadPDF(src.toFile())) {
                if (pageNo < 1 || pageNo > doc.getNumberOfPages()) {
                    throw new IllegalArgumentException("页码超出原文范围");
                }
                BufferedImage full = new PDFRenderer(doc)
                        .renderImageWithDPI(pageNo - 1, FIGURE_DPI, ImageType.RGB);
                int x = Math.max(0, rect[0]);
                int y = Math.max(0, rect[1]);
                int right = (int) Math.min((long) rect[0] + rect[2], full.getWidth());
                int bottom = (int) Math.min((long) rect[1] + rect[3], full.getHeight());
                int w = right - x;
                int h = bottom - y;
                if (w <= 0 || h <= 0) {
                    throw new IllegalArgumentException("裁剪区域位于页面之外");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(full.getSubimage(x, y, w, h), "png", out);
                png = out.toByteArray();
            }
            Files.createDirectories(cache.getParent());
            Files.write(cache, png, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return png;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("区域渲染失败: " + e.getMessage(), e);
        }
    }

    /** 统一检索用的轻量列表（正文为截断片段） */
    public List<Map<String, Object>> searchFiles(String kw, int limit) {        return fileInfoMapper.searchFiles(kw == null ? "" : kw.trim(), limit);
    }

    /** 自动召回候选（最近 N 条，正文截断） */
    public List<Map<String, Object>> retrievalScan(int limit) {
        return fileInfoMapper.retrievalScan(limit);
    }

    /**
     * 自动召回候选（**按检索词先过滤**）。
     * <p>传入词表时用整列 LIKE 先筛（召回不被"只取前 2000 字"限制），词表为空时退化为"最近 N 条"。
     */
    public List<Map<String, Object>> retrievalCandidates(List<String> terms, int limit) {
        if (terms == null || terms.isEmpty()) {
            return retrievalScan(limit);
        }
        return fileInfoMapper.retrievalScanByTerms(terms, limit);
    }

/**
     * 保存阅读位置（页码 / 缩放 / 阅读模式）。
     * <p><b>故意不触发 publishChanged</b>：翻个页不是"知识变更"，没必要让增量索引与 wiki 跟着跑。
     */
    public Map<String, Object> saveReadingState(Long id, Integer page, java.math.BigDecimal scale, String mode) {
        FileInfo info = require(id);
        info.setReadPage(page);
        info.setReadScale(scale);
        info.setReadMode(StringUtils.hasText(mode) ? mode.trim() : null);
        fileInfoMapper.updateById(info);
        return Map.of("ok", true, "page", page == null ? 0 : page,
                "scale", scale == null ? 0 : scale, "mode", info.getReadMode() == null ? "" : info.getReadMode());
    }
    /** 下载/内联打开的结果载体 */
    public DownloadItem download(Long id) {
        FileInfo info = require(id);
        Path path = storageDir().resolve(info.getStoreName());
        if (!Files.exists(path)) {
            throw new IllegalStateException("文件已丢失: " + info.getStoreName());
        }
        String encodedName = URLEncoder.encode(info.getOriginName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        return new DownloadItem(new FileSystemResource(path), encodedName, info.getOriginName(),
                mediaTypeOf(info.getOriginName()));
    }

    /** 删除：删记录 + 删磁盘文件（图谱里的孤儿边在 KgService 查询时被过滤掉） */
    @Transactional
    public void delete(Long id) {
        FileInfo info = require(id);
        fileInfoMapper.deleteById(id);
        try {
            Files.deleteIfExists(storageDir().resolve(info.getStoreName()));
        } catch (IOException e) {
            log.warn("磁盘文件删除失败(记录已删): {}", info.getStoreName(), e);
        }
        publishChanged(info.getCategoryId());
    }

    /** 列表用的字段（**不含正文**：正文列是 MEDIUMTEXT，列表永远不需要它） */
    public Map<String, Object> toMap(FileInfo info) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", info.getId());
        m.put("originName", info.getOriginName());
        m.put("size", info.getSize() == null ? 0L : info.getSize());
        m.put("ext", info.getExt() == null ? "" : info.getExt());
        m.put("categoryId", info.getCategoryId() == null ? 0L : info.getCategoryId());
        m.put("summary", info.getSummary() == null ? "" : info.getSummary());
        m.put("textStatus", info.getTextStatus() == null ? "" : info.getTextStatus());
        m.put("textChars", info.getTextChars() == null ? 0 : info.getTextChars());
        m.put("textError", info.getTextError() == null ? "" : info.getTextError());
        m.put("extractedAt", info.getExtractedAt() == null ? "" : info.getExtractedAt().toString());
        m.put("createdAt", info.getCreatedAt() == null ? "" : info.getCreatedAt().toString());
        // 阅读位置：阅读器打开时从这里恢复（只存页码/缩放/模式，不存滚动像素）
        m.put("readPage", info.getReadPage());
        m.put("readScale", info.getReadScale());
        m.put("readMode", info.getReadMode());
        return m;
    }

    private String categoryNameOf(Long categoryId) {
        if (categoryId == null || categoryId <= 0) {
            return "";
        }
        org.dyh.learnhub.entity.Category c = categoryMapper.selectById(categoryId);
        return c == null || c.getName() == null ? "" : c.getName();
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 按扩展名推断 Content-Type。
     * <p>内联阅读时必须给对，否则 PDF 会被浏览器当成二进制下载 —— 用户点"打开"只会得到一个下载框。
     * 认不出的一律当二进制：宁可不渲染，也不要拿一个错的类型去猜。
     */
    private static MediaType mediaTypeOf(String name) {
        return switch (extOf(name == null ? "" : name)) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "svg" -> MediaType.parseMediaType("image/svg+xml");
            case "txt", "log", "csv", "tsv", "md", "markdown", "json", "xml", "yml", "yaml" ->
                    new MediaType("text", "plain", StandardCharsets.UTF_8);
            case "html", "htm" -> MediaType.TEXT_HTML;
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    /**
     * @param mediaType 按扩展名推断的类型：**内联阅读时必须给对**，否则 PDF 会被当成二进制下载
     */
    public record DownloadItem(Resource resource, String encodedName, String originName,
                               MediaType mediaType) {
    }
}
