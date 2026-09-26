package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    private final ApplicationEventPublisher events;

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
        String ext = extOf(originName);
        String storeName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);

        Path target = storageDir().resolve(storeName);
        try {
            try (var in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("文件保存失败: " + e.getMessage(), e);
        }

        FileInfo info = new FileInfo();
        info.setOriginName(originName);
        info.setStoreName(storeName);
        info.setSize(file.getSize());
        info.setExt(ext);
        info.setCategoryId(categoryId);
        info.setTextStatus("pending");
        fileInfoMapper.insert(info);
        // 抽取放在入库之后：即使抽取失败/超时，资料本身也已经存在（可重试），不会出现"文件丢了但记录在"
        extractInto(info, target);
        publishChanged(categoryId);
        log.info("文件上传成功: {} ({} bytes) -> {}，正文 {}", originName, file.getSize(), storeName, info.getTextStatus());
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

    /** 统一检索用的轻量列表（正文为截断片段） */
    public List<Map<String, Object>> searchFiles(String kw, int limit) {
        return fileInfoMapper.searchFiles(kw == null ? "" : kw.trim(), limit);
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
