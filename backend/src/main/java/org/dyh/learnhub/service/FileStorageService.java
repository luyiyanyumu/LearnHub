package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.awt.Color;
import java.awt.Graphics2D;
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
import java.util.HexFormat;
import java.security.MessageDigest;
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
    private final WordLayoutExtractor wordLayoutExtractor;
    private final TextMathLayout textMathLayout;
    private final ApplicationEventPublisher events;
    private final LearningActivityService learningActivity;
    /** 改名时要同步 kb_chunk 里的冗余标题（检索结果展示用） */
    private final org.dyh.learnhub.mapper.KbChunkMapper kbChunkMapper;

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
        return fileInfoMapper.selectList(listWrapper(categoryId, kw)).stream().map(this::toMap).collect(Collectors.toList());
    }

    /**
     * **分页**的资料列表（界面用）。与 {@link #list} 并存而不是替换它：
     * 那个方法还被智能体的 `list_files` 工具用着（它要全量再自己截断），改签名会连带炸掉那条链路。
     *
     * <p>为什么必须分页：界面是 `el-table` 一次渲染全部行，1,000 份资料就是 7,000+ 个单元格 DOM，
     * 没有虚拟滚动 —— 首屏卡顿、滚动掉帧。分页把渲染量压到一个页大小。
     *
     * @param current 页码，从 1 开始
     * @param size    每页条数。上限 **100** —— 与 `MybatisPlusConfig` 里
     *                `PaginationInnerInterceptor.setMaxLimit(100L)` 保持一致（那里是兜底，
     *                这里显式夹一次，免得以后有人调大那边时这里悄悄变成全量）
     */
    public Map<String, Object> page(Long categoryId, String kw, Integer current, Integer size) {
        long pageNo = current == null || current < 1 ? 1 : current;
        long pageSize = size == null || size < 1 ? 20 : Math.min(size, PAGE_SIZE_MAX);
        Page<FileInfo> p = fileInfoMapper.selectPage(new Page<>(pageNo, pageSize), listWrapper(categoryId, kw));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", p.getRecords().stream().map(this::toMap).toList());
        out.put("total", p.getTotal());
        out.put("page", p.getCurrent());
        out.put("size", p.getSize());
        return out;
    }

    /** 列表与分页共用的过滤条件（两处必须一致，否则"翻页后条数对不上"这类 bug 极难查） */
    private LambdaQueryWrapper<FileInfo> listWrapper(Long categoryId, String kw) {
        LambdaQueryWrapper<FileInfo> wrapper = Wrappers.<FileInfo>lambdaQuery()
                .orderByDesc(FileInfo::getCreatedAt);
        if (categoryId != null && categoryId > 0) {
            wrapper.eq(FileInfo::getCategoryId, categoryId);
        }
        if (StringUtils.hasText(kw)) {
            wrapper.like(FileInfo::getOriginName, kw.trim());
        }
        return wrapper;
    }

    /** `origin_name` 的列上限（VARCHAR(255)）—— 超了 MySQL 会直接报 Data too long */
    private static final int NAME_COLUMN_LIMIT = 255;

    /** 每页条数上限：与 `MybatisPlusConfig` 的 `setMaxLimit(100L)` 对齐 */
    private static final int PAGE_SIZE_MAX = 100;

    /**
     * 文件名落库前的规范化：**去控制字符** + **截到列上限**（保留扩展名）。
     *
     * <p>两个都必须做，且都由实测暴露：
     * <ul>
     *   <li><b>控制字符</b>：`StringUtils.cleanPath` 只处理路径分隔符，不碰 `\n`。而文件名里的换行
     *       在前端表格里会被 CSS（`white-space: normal`）折叠成一个空格 —— 界面显示的名字与真实名字
     *       不一致，用户按界面看到的名字去文件系统里找会找不到。</li>
     *   <li><b>超长</b>：上传路径此前对长度没有任何限制，而列是 VARCHAR(255)。文件名超过 255
     *       会让 INSERT 直接失败（严格模式下报 Data too long → 500），用户看到的是"上传失败"却不知为何。</li>
     * </ul>
     * 截断时保留扩展名：扩展名参与"用哪个解析器抽正文"的判断，丢了它就抽不出正文。
     */
    static String normalizeOriginName(String raw) {
        String s = sanitizeControls(raw);
        if (s.isEmpty()) {
            return "unnamed";
        }
        if (s.length() <= NAME_COLUMN_LIMIT) {
            return s;
        }
        String ext = extOf(s);
        if (ext.isEmpty()) {
            return s.substring(0, NAME_COLUMN_LIMIT);
        }
        int keep = NAME_COLUMN_LIMIT - ext.length() - 1;
        return keep <= 0 ? s.substring(0, NAME_COLUMN_LIMIT) : s.substring(0, keep) + "." + ext;
    }

    /** 去掉控制字符（`\n` `\r` `\t` `\0` 与 DEL）并把连续空白压成一个空格（上传与改名共用同一条规则） */
    static String sanitizeControls(String raw) {
        String s = raw == null ? "" : raw;
        // 替换成空格而不是删除，避免两个词粘成一个
        s = s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        // 上面是"替换成空格"，所以 `x\u0000y.pdf` 会变成 `x y .pdf` ——
        // 扩展名前的空格一定是这个替换留下的副产物（真实文件名不会这么写），去掉它
        return s.replaceAll("\\s+(\\.\\w{1,20})$", "$1");
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
        // cleanPath 只管路径分隔符；normalizeOriginName 再去控制字符并截到列上限。
        // 这里是**所有导入路径的唯一汇聚点**（网页上传 / 智能体存论文 / MCP / 生成 Word），
        // 所以放在这一处就够了，不必在每个调用方各写一遍。
        String name = normalizeOriginName(
                StringUtils.cleanPath(StringUtils.hasText(originName) ? originName.trim() : "unnamed"));
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

    /**
     * 改显示文件名（**只改基名，不允许改扩展名**）。
     *
     * <h3>为什么不允许改扩展名</h3>
     * 磁盘上的真实文件用 {@code storeName}（随机名）保存，改名只动 {@code originName}；
     * 而抽正文时 {@code extractInto} 是按 {@code info.getExt()} **选解析器**的
     * （pdf→PDFBox、docx→POI…）。若允许把 {@code 报告.pdf} 改成 {@code 报告.docx}，
     * 库里的 ext 就与磁盘内容的真实格式不一致 → 下次重抽会用 Word 解析器去读 PDF，
     * 报出莫名的错。所以这里明确拒绝，并把原因告诉用户（而不是静默接受）。
     *
     * <h3>为什么顺带改知识块里的标题</h3>
     * {@code kb_chunk.title} 是资料标题的**冗余列**（检索结果展示用）。不改的话，
     * 改了名之后检索命中的还是旧标题，用户会以为没生效。
     */
    public Map<String, Object> rename(Long id, String newName) {
        FileInfo info = require(id);
        String raw = newName == null ? "" : newName.trim();
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        // 去掉路径分隔符：浏览器传入或用户粘贴可能带上目录（防目录穿越）
        String cleaned = StringUtils.cleanPath(raw).replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (!StringUtils.hasText(cleaned) || cleaned.startsWith(".")) {
            throw new IllegalArgumentException("文件名不合法（不能只有扩展名或隐藏文件）");
        }
        String newExt = extOf(cleaned);
        String oldExt = info.getExt() == null ? "" : info.getExt();
        if (!newExt.equalsIgnoreCase(oldExt)) {
            throw new IllegalArgumentException("不能改扩展名（现在 ." + oldExt + "）；"
                    + "抽正文是按扩展名选解析器的，改了会出现「按 Word 解析 PDF」这类错。"
                    + "只改名字部分即可，例如把「旧名." + oldExt + "」改成「新名." + oldExt + "」。");
        }
        // 去掉结尾的 ".ext" 就是新基名；基名不能为空。
        // 与上传路径用同一条清洗规则（去控制字符）—— 否则"上传时干净、改名时能塞进换行"，
        // 界面显示的名字又会与真实名字不一致。
        String base = sanitizeControls(
                cleaned.substring(0, cleaned.length() - (oldExt.isEmpty() ? 0 : oldExt.length() + 1)));
        if (!StringUtils.hasText(base)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        if (cleaned.length() > 200) {
            throw new IllegalArgumentException("文件名太长（≤200 字符）");
        }

        info.setOriginName(base + (oldExt.isEmpty() ? "" : "." + oldExt));
        fileInfoMapper.updateById(info);

        // 同步知识块里的冗余标题（仅 file 来源）
        try {
            int changed = kbChunkMapper.refreshTitleBySource("file", info.getId(), info.getOriginName());
            if (changed > 0) {
                log.info("资料 #{} 改名后同步了 {} 个知识块的标题", id, changed);
            }
        } catch (Exception e) {
            log.warn("改名后同步知识块标题失败（检索展示可能仍是旧标题）：{}", e.getMessage());
        }
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
     * PDF 从字形坐标还原，Word 从 OOXML 结构还原，Markdown 保留原有 LaTeX。
     */
    public Map<String, Object> layoutOf(Long id) {
        FileInfo info = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", info.getId());
        m.put("ext", info.getExt() == null ? "" : info.getExt());
        m.put("textStatus", info.getTextStatus() == null ? "" : info.getTextStatus());
        String ext = info.getExt() == null ? "" : info.getExt().toLowerCase(Locale.ROOT);
        if (!Set.of("pdf", "docx", "docm", "md", "markdown").contains(ext)) {
            m.put("status", "unsupported");
            m.put("error", "该格式使用分段正文阅读");
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
        // 版面还原要逐页重建几何（PDF 从字形坐标、Word 从 OOXML），还可能带上公式识别 —— 秒级起步，
        // 而它只是"把已经上传的文件再看一遍"。按「文件内容版本 + 公式识别结果版本」缓存，见 layoutCachePath。
        Map<String, Object> cached = readLayoutCache(id, path);
        if (cached != null) {
            // 元信息是"活的"：id/ext/textStatus 每次按当前行回填，缓存里只认版面那几项
            cached.put("id", info.getId());
            cached.put("ext", info.getExt() == null ? "" : info.getExt());
            cached.put("textStatus", info.getTextStatus() == null ? "" : info.getTextStatus());
            return cached;
        }
        PdfLayoutExtractor.Layout layout;
        try {
            if ("pdf".equals(ext)) {
                layout = pdfLayoutExtractor.extract(path);
                layout = withRecognizedFormulas(id, path, layout);
            }
            else if ("docx".equals(ext) || "docm".equals(ext)) layout = wordLayoutExtractor.extract(path);
            else {
                DocumentTextService.Extracted extracted = documentTextService.extract(path, ext, Files.size(path));
                if (!DocumentTextService.STATUS_OK.equals(extracted.status())) {
                    layout = new PdfLayoutExtractor.Layout(extracted.status(), extracted.error(), List.of(), 0, 0);
                } else layout = textMathLayout.extract(extracted.text());
            }
        } catch (Exception e) {
            log.warn("结构化正文抽取失败: {} - {}", info.getId(), e.toString());
            layout = new PdfLayoutExtractor.Layout("failed", e.getMessage(), List.of(), 0, 0);
        }
        m.put("status", layout.status());
        m.put("error", layout.error());
        m.put("pages", layout.pages());
        m.put("chars", layout.chars());
        m.put("pageCount", layout.pageCount());
        writeLayoutCache(id, path, m);
        return m;
    }

    /**
     * 「抽取正文」的结果缓存文件（`uploads/.derived/{id}/layout-{revision}-{formulaStamp}.json`）。
     *
     * <p>键里两段都必要：<b>revision</b> 来自文件内容（大小 + mtime 的哈希，见 {@link #sourceRevision}），
     * 换一份文件或重传同名文件都**不会**复用旧版面；<b>formulaStamp</b> 是公式识别缓存目录里最新的
     * 修改时间 —— 公式识别是异步补上的，识别结果晚到时要重算，不能把"还没识别的版本"缓存成最终结果。
     *
     * <p>目录沿用公式识别那套 `.derived/{id}/`：删除资料时一起清掉，不会留孤儿文件。
     */
    private Path layoutCachePath(Long id, Path source) {
        long formulaStamp = 0L;
        Path formulaDir = storageDir().resolve(".derived").resolve(String.valueOf(id)).resolve("formula-ocr");
        if (Files.isDirectory(formulaDir)) {
            try (var files = Files.list(formulaDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    try {
                        formulaStamp = Math.max(formulaStamp, Files.getLastModifiedTime(f).toMillis());
                    } catch (IOException ignored) {
                        // 单个文件读不到时间戳就当它不存在
                    }
                }
            } catch (IOException e) {
                log.warn("公式识别缓存目录读取失败，本次按无识别结果处理: {}", e.toString());
            }
        }
        return storageDir().resolve(".derived").resolve(String.valueOf(id))
                .resolve("layout-" + sourceRevision(source) + "-" + formulaStamp + ".json");
    }

    /** 分段翻译 + **落盘缓存**（阅读器用）。真正翻译由调用方以 supplier 传进来。
     *
     * <p>为什么必须缓存：对照阅读时同一段会被反复选中（来回翻页、切模式、重开阅读器），
     * 每选一次就调一次模型 = 白花 token；译文又是确定性任务，同一段输入结果稳定。
     * 缓存键 = 「目标语言 + 原文 + 译文身份（档案/模型）」的哈希，落在
     * `uploads/.derived/{id}/translate/`（与版面/公式缓存同一处，删资料时一起清）。
     *
     * <p>键里带译文身份的意义：换了「阅读器翻译」的档案就自动重译，不会拿旧模型的译文糊弄。
     * 返回体多一个 `cached` 字段，界面想知道这次有没有花钱可以看它。
     *
     * <p>翻译器用 supplier 注入而不是构造器依赖：这个类的构造器被好几个测试直接 new，
     * 为一个缓存再牵一条依赖会把它们全改一遍 —— 代价不值得。
     */
    public Map<String, Object> translateCached(Long id, String text, String targetLang, String identity,
                                              java.util.function.Supplier<Map<String, Object>> compute) {
        String src = text == null ? "" : text;
        String lang = (targetLang == null || targetLang.isBlank()) ? "简体中文" : targetLang.trim();
        String key = sha256Hex(src + "\u0000" + lang + "\u0000" + (identity == null ? "" : identity));
        Path cache = storageDir().resolve(".derived").resolve(String.valueOf(id)).resolve("translate").resolve(key + ".json");
        if (Files.isRegularFile(cache)) {
            try {
                Map<String, Object> hit = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                        cache.toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                hit.put("cached", true);
                return hit;
            } catch (Exception e) {
                log.warn("译文缓存读取失败，改为重译: {} - {}", cache, e.toString());
            }
        }
        Map<String, Object> out = compute.get();
        try {
            Files.createDirectories(cache.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(cache.toFile(), out);
        } catch (Exception e) {
            log.warn("译文缓存写入失败（不影响本次结果）: {} - {}", cache, e.toString());
        }
        out.put("cached", false);
        return out;
    }

    /** 文本的 SHA-256 十六进制（缓存键只用它，不落原文） */
    private static String sha256Hex(String text) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 读版面缓存；缺失或解析失败都返回 null（→ 上层重算，缓存问题绝不影响阅读） */
    private Map<String, Object> readLayoutCache(Long id, Path source) {
        Path cache = layoutCachePath(id, source);
        if (!Files.isRegularFile(cache)) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    cache.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("版面缓存读取失败，改为重算: {} - {}", cache, e.toString());
            return null;
        }
    }

    /** 写版面缓存；失败只记日志（这次读取照样返回新算的结果） */
    private void writeLayoutCache(Long id, Path source, Map<String, Object> layout) {
        Path cache = layoutCachePath(id, source);
        try {
            Files.createDirectories(cache.getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writeValue(cache.toFile(), layout);
        } catch (Exception e) {
            log.warn("版面缓存写入失败（不影响本次结果）: {} - {}", cache, e.toString());
        }
    }

    /** 渲染插图的分辨率：150 够看清图里的字，单页位图也就 8MB 左右，不至于把内存吃掉 */
    private static final float FIGURE_DPI = 150f;
    private static final float FORMULA_DPI = 300f;
    private static final long MAX_FORMULA_PIXELS = 5_000_000;

    /** Render only the selected formula into a high-resolution canvas, rather than a full-page bitmap. */
    public byte[] formulaImage(Long id, int pageNo, double[] rect) {
        validateFormulaRect(pageNo, rect);
        FileInfo info = require(id);
        if (!"pdf".equalsIgnoreCase(info.getExt())) throw new IllegalArgumentException("公式原图识别目前支持 PDF");
        Path source = storageDir().resolve(info.getStoreName());
        double scale = FORMULA_DPI / 72.0;
        try (PDDocument doc = Loader.loadPDF(source.toFile())) {
            if (pageNo > doc.getNumberOfPages()) throw new IllegalArgumentException("页码超出原文范围");
            var box = doc.getPage(pageNo - 1).getCropBox();
            boolean sideways = Math.floorMod(doc.getPage(pageNo - 1).getRotation(), 180) != 0;
            double pageWidth = sideways ? box.getHeight() : box.getWidth();
            double pageHeight = sideways ? box.getWidth() : box.getHeight();
            // Preserve tiny subscript glyphs and equation numbers at the edges of the detected block.
            int left = Math.max(0, (int) Math.floor((rect[0] - 2) * scale));
            int top = Math.max(0, (int) Math.floor((rect[1] - 2) * scale));
            int right = (int) Math.min(Math.ceil(pageWidth * scale), Math.ceil((rect[2] + 2) * scale));
            int bottom = (int) Math.min(Math.ceil(pageHeight * scale), Math.ceil((rect[3] + 2) * scale));
            int width = right - left, height = bottom - top;
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("公式区域位于页面之外");
            if ((long) width * height > MAX_FORMULA_PIXELS)
                throw new IllegalArgumentException("识别区域过大，请缩小到单个公式");
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.setBackground(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.translate(-left, -top);
                new PDFRenderer(doc).renderPageToGraphics(pageNo - 1, graphics, (float) scale, (float) scale);
            } finally { graphics.dispose(); }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return bytes.toByteArray();
        } catch (IllegalArgumentException e) { throw e; }
        catch (IOException e) { throw new IllegalStateException("公式原图渲染失败，请查看原文", e); }
    }

    /** Source content, page and geometry determine cache identity; replacing a file never reuses an old result. */
    public String formulaRecognitionKey(Long id, int pageNo, double[] rect) {
        validateFormulaRect(pageNo, rect);
        FileInfo info = require(id);
        if (!"pdf".equalsIgnoreCase(info.getExt())) throw new IllegalArgumentException("公式原图识别目前支持 PDF");
        return formulaKey(sourceRevision(storageDir().resolve(info.getStoreName())), pageNo, rect);
    }

    public Path formulaRecognitionCachePath(Long id, String key) {
        if (id == null || id < 1 || key == null || !key.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("公式缓存标识无效");
        return storageDir().resolve(".derived").resolve(String.valueOf(id)).resolve("formula-ocr").resolve(key + ".json");
    }

    private static void validateFormulaRect(int pageNo, double[] rect) {
        if (pageNo < 1 || rect == null || rect.length != 4
                || !java.util.Arrays.stream(rect).allMatch(Double::isFinite)
                || rect[2] <= rect[0] || rect[3] <= rect[1]
                || java.util.Arrays.stream(rect).anyMatch(value -> Math.abs(value) > 100_000))
            throw new IllegalArgumentException("公式区域不合法");
    }

    private static String sourceRevision(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[16_384];
                for (int count; (count = input.read(buffer)) >= 0;) if (count > 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) { throw new IllegalStateException("无法读取公式原文件", e); }
    }

    private static String formulaKey(String revision, int page, double[] rect) {
        String value = "formula-image-v1:" + revision + ":" + page;
        for (double coordinate : rect) value += ":" + Double.toHexString(coordinate);
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private PdfLayoutExtractor.Layout withRecognizedFormulas(Long id, Path source, PdfLayoutExtractor.Layout layout) {
        if (layout.pages().stream().noneMatch(page -> page.blocks().stream().anyMatch(block -> "formula".equals(block.type())))) return layout;
        Path directory = storageDir().resolve(".derived").resolve(String.valueOf(id)).resolve("formula-ocr");
        if (!Files.isDirectory(directory)) return layout;
        String revision = sourceRevision(source);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<PdfLayoutExtractor.PageLayout> pages = new ArrayList<>();
        for (var page : layout.pages()) {
            List<PdfLayoutExtractor.Block> blocks = new ArrayList<>();
            for (var block : page.blocks()) {
                var shown = block;
                if ("formula".equals(block.type()) && block.rect() != null) {
                    Path cached = formulaRecognitionCachePath(id, formulaKey(revision, page.page(), block.rect()));
                    try {
                        if (Files.isRegularFile(cached) && Files.size(cached) <= 65_536) {
                            var saved = json.readTree(cached.toFile());
                            String latex = saved.path("latex").asText("");
                            String status = saved.path("status").asText("");
                            if (!latex.isBlank() && latex.length() <= 12_000 && Set.of("recognized", "partial").contains(status)) {
                                String description = "视觉模型 " + saved.path("profile").asText("") + " / "
                                        + saved.path("model").asText("") + " 识别（已缓存），请核对原图。"
                                        + saved.path("message").asText("");
                                shown = new PdfLayoutExtractor.Block(block.type(), block.text(), block.level(), block.src(),
                                        block.page(), block.rect(), latex, status, description);
                            }
                        }
                    } catch (Exception e) { log.debug("忽略不可用公式识别缓存: {}", cached.getFileName()); }
                }
                blocks.add(shown);
            }
            pages.add(new PdfLayoutExtractor.PageLayout(page.page(), page.columns(), blocks));
        }
        return new PdfLayoutExtractor.Layout(layout.status(), layout.error(), pages, layout.chars(), layout.pageCount());
    }

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
     * <p>公式的 LaTeX 还原保留原图作为对照，无法确定的结构也能按原图阅读。
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

    /**
     * 删除资料：删数据库记录，磁盘文件**移进回收站**（不是直接删掉）。
     *
     * <p>为什么不是真删：删资料是不可逆操作，而误删的代价很大 —— 实测踩过：
     * 一条 14MB 的 PDF 被误删后，正文虽然还在知识库里，**原文件本身再也拿不回来**。
     * 回收站给一次后悔的机会：文件改名为 {@code <storeName>.deleted-<时间戳>} 移到
     * {@code uploads/.trash/} 下，恢复只需把它移回上一级目录（storeName 就是原名，
     * 记录已删所以不会同名冲突）。
     *
     * <p>不做自动清理：什么时候清由人来定 —— 自动删"超过 N 天"的文件，
     * 等于把"后悔窗口"偷偷关掉，与这里的目的相反。要清理直接删 {@code uploads/.trash/} 即可。
     */
    @Transactional
    public void delete(Long id) {
        FileInfo info = require(id);
        fileInfoMapper.deleteById(id);
        Path file = storageDir().resolve(info.getStoreName());
        try {
            if (Files.exists(file)) {
                Path trash = storageDir().resolve(TRASH_DIR);
                Files.createDirectories(trash);
                Path target = trash.resolve(info.getStoreName()
                        + ".deleted-" + System.currentTimeMillis());
                Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("资料 #{} 已移入回收站: {}（恢复：移回 {}/ 即可）", id, target.getFileName(), TRASH_DIR);
            }
        } catch (IOException e) {
            log.warn("移入回收站失败(记录已删): {}", info.getStoreName(), e);
        }
        publishChanged(info.getCategoryId());
    }

    /** 回收站目录名（相对上传目录）：删除的资料放这里，便于人工恢复 */
    public static final String TRASH_DIR = ".trash";

    /** 列出回收站里的文件（供界面/排查用；按修改时间倒序） */
    public List<Map<String, Object>> trash() {
        Path dir = storageDir().resolve(TRASH_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> {
                        try {
                            return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", p.getFileName().toString());
                        try {
                            m.put("size", Files.size(p));
                            m.put("deletedAt", Files.getLastModifiedTime(p).toInstant().toString());
                        } catch (IOException e) {
                            m.put("size", 0L);
                        }
                        return m;
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("读取回收站失败: {}", e.getMessage());
            return List.of();
        }
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
