package org.dyh.learnhub.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 技能（Skill）加载器：把 {@code skills/&lt;id&gt;/SKILL.md} 读成"送给模型的系统提示词"。
 * <p>
 * 背景：润色/格式两条提示词原先存在 {@code app_setting} 表里、由设置面板粘贴维护，
 * 结果被人把同一份润色文档同时导进了两个框（{@code ai.polish_prompt} 与 {@code ai.format_prompt}
 * 存着完全相同的 4051 字），「整理格式」就一直在用润色提示词跑，界面上还都显示「已自定义」。
 * 改成技能文件后：可进版本库、可 diff、一个技能只对应一个用途，长度下界这类与提示词强耦合的
 * 参数也随文件一起走，不用再靠"记得同步改 Java 常量"。
 * <p>
 * 设计取舍：
 * <ul>
 *   <li>**每次请求实时读盘**，不做缓存 —— 文件只有几 KB，相对秒级的模型调用可以忽略，
 *       换来的是"改完存盘即生效、不用重启后端"。</li>
 *   <li>**只解析扁平的 {@code key: value}**，不引 YAML 库：够用、零依赖，也不会因为缩进写错而静默失效。</li>
 *   <li>**找不到技能就明确报错**，不静默回退到某个内置常量：静默回退会让用户以为改动生效了，
 *       实际跑的还是旧提示词 —— 那正是这次要修掉的那类问题。</li>
 * </ul>
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    /** 技能文件名（固定）：正文即提示词 */
    public static final String SKILL_FILE = "SKILL.md";

    /** 润色技能 id */
    public static final String SKILL_POLISH = "markdown-polish";
    /** 整理格式技能 id */
    public static final String SKILL_BEAUTIFY = "markdown-beautify";

    /** 显式配置的技能目录（留空则按候选顺序自动探测） */
    @Value("${learnhub.skills-dir:}")
    private String configuredDir;

    /** 实际生效的技能目录；为 null 表示没找到 */
    private volatile Path skillsDir;

    @PostConstruct
    public void init() {
        skillsDir = resolveSkillsDir();
        if (skillsDir == null) {
            log.warn("未找到技能目录（skills/）：润色与整理格式将不可用。"
                     + "请用 learnhub.skills-dir 指定，或确认 skills/ 在启动目录或其上级。");
            return;
        }
        List<String> ids = list().stream().map(SkillInfo::id).toList();
        log.info("技能目录：{}（{} 个技能：{}）", skillsDir, ids.size(),
                 ids.isEmpty() ? "空" : String.join(", ", ids));
    }

    /**
     * 按顺序探测技能目录，第一个含 SKILL.md 的生效。
     * <p>
     * 多个候选是因为启动目录不确定：README 是在 {@code backend/} 下 `java -jar target/...`，
     * 但直接 `mvn spring-boot:run` 或从仓库根目录启动也都很常见。
     */
    private Path resolveSkillsDir() {
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(configuredDir)) {
            candidates.add(configuredDir.trim());
        }
        String fromEnv = System.getenv("LEARNHUB_SKILLS_DIR");
        if (StringUtils.hasText(fromEnv)) {
            candidates.add(fromEnv.trim());
        }
        candidates.add("skills");
        candidates.add("../skills");
        candidates.add("../../skills");

        for (String c : candidates) {
            Path p = Paths.get(c).toAbsolutePath().normalize();
            if (Files.isDirectory(p) && hasAnySkill(p)) {
                return p;
            }
        }
        return null;
    }

    private boolean hasAnySkill(Path dir) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.anyMatch(d -> Files.isRegularFile(d.resolve(SKILL_FILE)));
        } catch (IOException e) {
            return false;
        }
    }

    /** 技能元数据（界面展示 + 后端取值） */
    public record SkillInfo(String id, String name, String description, String appliesTo,
                            Double minRatio, String path, int chars) {
    }

    /** 列出全部技能（按目录名排序），供设置面板展示 */
    public List<SkillInfo> list() {
        List<SkillInfo> out = new ArrayList<>();
        Path dir = skillsDir;
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        Path f = p.resolve(SKILL_FILE);
                        if (!Files.isRegularFile(f)) {
                            return;
                        }
                        try {
                            Parsed parsed = parse(Files.readString(f, StandardCharsets.UTF_8));
                            String id = p.getFileName().toString();
                            out.add(new SkillInfo(
                                    id,
                                    StringUtils.hasText(parsed.meta.get("name"))
                                            ? parsed.meta.get("name") : id,
                                    parsed.meta.getOrDefault("description", ""),
                                    parsed.meta.getOrDefault("applies_to", ""),
                                    parseRatio(parsed.meta.get("min_ratio")),
                                    f.toAbsolutePath().toString(),
                                    parsed.body.length()));
                        } catch (IOException e) {
                            log.warn("技能文件读取失败：{}（{}）", f, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("技能目录列举失败：{}（{}）", dir, e.getMessage());
        }
        return out;
    }

    public SkillInfo get(String id) {
        for (SkillInfo s : list()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 取技能的提示词正文（frontmatter 之外的全部内容）。
     *
     * @throws IllegalStateException 目录或文件缺失、正文为空时抛出 —— 宁可让用户看到明确错误，
     *                               也不能静默用别的提示词顶替（那会让改动"看起来生效了"却实际没生效）
     */
    public String prompt(String id) {
        Path dir = skillsDir;
        if (dir == null) {
            throw new IllegalStateException(
                    "找不到技能目录：请确认 skills/ 存在（或用 learnhub.skills-dir 指定），"
                    + "当前需要技能 " + id);
        }
        Path f = dir.resolve(id).resolve(SKILL_FILE);
        if (!Files.isRegularFile(f)) {
            throw new IllegalStateException("找不到技能文件：" + f);
        }
        try {
            Parsed parsed = parse(Files.readString(f, StandardCharsets.UTF_8));
            if (!StringUtils.hasText(parsed.body.trim())) {
                throw new IllegalStateException("技能文件正文为空：" + f);
            }
            return parsed.body.trim();
        } catch (IOException e) {
            throw new IllegalStateException("技能文件读取失败：" + f + "（" + e.getMessage() + "）");
        }
    }

    /** 取技能声明的长度下界比例；未声明或非法时用兜底值 */
    public double minRatio(String id, double fallback) {
        SkillInfo s = get(id);
        return (s != null && s.minRatio() != null && s.minRatio() > 0) ? s.minRatio() : fallback;
    }

    /** 技能目录名白名单：小写字母/数字开头，允许 . _ -（同时挡掉路径穿越） */
    private static final java.util.regex.Pattern ID_PATTERN =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** 元数据字段的固定顺序（写回时按这个排，便于人工 diff） */
    private static final List<String> META_ORDER = List.of("name", "description", "applies_to", "min_ratio");

    /**
     * 保存（新建或替换）一个技能的 SKILL.md —— 设置面板「上传技能」按钮的后端实现。
     * <p>
     * 元数据优先级：**上传内容自带的 frontmatter** &gt; 显式传入的覆盖项 &gt; 原有 frontmatter &gt; 按内容/目录名生成。
     * 这样"只换提示词"时不会顺手把 {@code applies_to} / {@code min_ratio} 丢掉 ——
     * 后者丢了会让长度守卫静默回到兜底值，而用户完全看不出来（这正是把提示词从数据库搬出来的起因）。
     * <p>
     * 覆盖前会把旧版本备份到 {@code skills/.history/<id>/SKILL-<时间戳>.md}：
     * {@code skills/} 目前不在版本库里，直接覆盖就再也找不回来了。
     *
     * @param id      技能目录名
     * @param content 上传的文本（带 frontmatter 则连元数据一起替换；不带则只替换正文）
     * @param over    显式覆盖的元数据（name / description / applies_to / min_ratio），可为 null
     */
    public SkillInfo save(String id, String content, Map<String, String> over) {
        String skillId = id == null ? "" : id.trim();
        if (!ID_PATTERN.matcher(skillId).matches()) {
            throw new IllegalArgumentException(
                    "技能名不合法（只能用小写字母、数字、. _ -，且以字母或数字开头）：" + id);
        }
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("上传内容为空，未做任何改动");
        }
        Path dir = writableDir();
        Path target = dir.resolve(skillId).resolve(SKILL_FILE).normalize();
        if (!target.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("技能名不合法：" + id);
        }

        Parsed existing = Files.isRegularFile(target) ? parse(readQuietly(target)) : null;
        Parsed uploaded = parse(content);
        boolean uploadedHasMeta = !uploaded.meta().isEmpty();

        Map<String, String> meta = new LinkedHashMap<>();
        if (existing != null) {
            meta.putAll(existing.meta());
        }
        if (uploadedHasMeta) {
            meta.putAll(uploaded.meta());
        }
        if (over != null) {
            over.forEach((k, v) -> {
                if (StringUtils.hasText(v)) {
                    meta.put(k, v.trim());
                }
            });
        }
        String body = (uploadedHasMeta ? uploaded.body() : content).trim();
        meta.putIfAbsent("name", skillId);
        if (!StringUtils.hasText(meta.get("description"))) {
            meta.put("description", firstLine(body));
        }

        if (existing != null) {
            backup(target, skillId);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, render(meta, body), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("技能写入失败：" + target + "（" + e.getMessage() + "）");
        }
        log.info("技能已保存：{}（正文 {} 字，元数据 {} 项）", target, body.length(), meta.size());
        return get(skillId);
    }

    /** 还没探测到技能目录时，落到 ./skills 并新建 —— 让"上传技能"在空环境里也能用 */
    private Path writableDir() {
        if (skillsDir != null) {
            return skillsDir;
        }
        Path fallback = Paths.get("skills").toAbsolutePath().normalize();
        log.warn("此前未探测到技能目录，将使用并创建：{}", fallback);
        return fallback;
    }

    /** 覆盖前留一份旧版到 skills/.history/<id>/ */
    private void backup(Path target, String id) {
        try {
            Path hist = target.getParent().getParent().resolve(".history").resolve(id);
            Files.createDirectories(hist);
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Files.copy(target, hist.resolve("SKILL-" + stamp + ".md"),
                       java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 备份失败不该挡住保存本身，但要留下痕迹
            log.warn("技能旧版备份失败（保存仍继续）：{}", e.getMessage());
        }
    }

    /** 按固定字段顺序渲染回 SKILL.md */
    private String render(Map<String, String> meta, String body) {
        StringBuilder sb = new StringBuilder("---\n");
        for (String key : META_ORDER) {
            String v = meta.get(key);
            if (StringUtils.hasText(v)) {
                sb.append(key).append(": ").append(v.trim()).append('\n');
            }
        }
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (!META_ORDER.contains(e.getKey()) && StringUtils.hasText(e.getValue())) {
                sb.append(e.getKey()).append(": ").append(e.getValue().trim()).append('\n');
            }
        }
        return sb.append("---\n\n").append(body).append('\n').toString();
    }

    /** 取正文第一行有效文字当默认描述（去掉标题符号） */
    private String firstLine(String body) {
        for (String line : body.split("\n")) {
            String t = line.replaceAll("^[#>*\\-\\s]+", "").trim();
            if (!t.isEmpty()) {
                return t.length() > 80 ? t.substring(0, 80) : t;
            }
        }
        return "";
    }

    private String readQuietly(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("旧技能文件读取失败（按空处理）：{}（{}）", f, e.getMessage());
            return "";
        }
    }

    private Double parseRatio(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("技能 frontmatter 的 min_ratio 不是数字：{}", raw);
            return null;
        }
    }

    /** frontmatter + 正文 */
    private record Parsed(Map<String, String> meta, String body) {
    }

    /**
     * 解析 SKILL.md：首行 `---` 到下一个 `---` 之间是扁平元数据，其余是正文。
     * <p>
     * 没有 frontmatter 时整个文件都是正文（这样最朴素的"纯提示词文件"也能直接用）。
     */
    private Parsed parse(String text) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        Map<String, String> meta = new LinkedHashMap<>();
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        if (i < lines.length && "---".equals(lines[i].trim())) {
            int end = -1;
            for (int j = i + 1; j < lines.length; j++) {
                if ("---".equals(lines[j].trim())) {
                    end = j;
                    break;
                }
            }
            if (end > 0) {
                for (int j = i + 1; j < end; j++) {
                    String line = lines[j];
                    int colon = line.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    // 去掉成对的引号，避免 description 里的冒号把值截断后还要用户去猜
                    if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    meta.put(key, value);
                }
                StringBuilder body = new StringBuilder();
                for (int j = end + 1; j < lines.length; j++) {
                    body.append(lines[j]);
                    if (j < lines.length - 1) {
                        body.append('\n');
                    }
                }
                return new Parsed(meta, body.toString());
            }
        }
        return new Parsed(meta, text);
    }
}
