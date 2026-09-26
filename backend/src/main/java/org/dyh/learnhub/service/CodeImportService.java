package org.dyh.learnhub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.entity.CodeRepo;
import org.dyh.learnhub.entity.CodeSnippet;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件夹导入：把一整个目录的源码**识别 → 过滤 → 入库 → 建索引**。
 *
 * <h3>为什么过滤是重点</h3>
 * 一个真实仓库里绝大多数文件是**噪声**：依赖目录（node_modules/venv）、构建产物（target/dist/out）、
 * 二进制资源（图片/字体/压缩包）、锁文件（package-lock.json 动辄上 MB）、压缩过的产物（*.min.js）。
 * 不过滤的话，一次导入就能把代码库冲垮 —— 而且这些内容**永远不该被检索到**
 *（"node_modules 里的某行"不是知识）。所以这里按三条规则过滤，并把**每一条被跳过的原因都记账**，
 * 导入完如实报给用户，而不是假装"全都进去了"。
 *
 * <h3>为什么按"一个文件一个片段"</h3>
 * 这样符号索引能落到真实文件与行号上（"splitWithHeadings 在哪定义"才有意义）。
 * 代价是条目数会变多，所以：① 重复导入同一路径是**更新**而不是新增；② 列表按项目筛选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeImportService {

    private final CodeLibraryService library;
    private final CodeIndexService indexService;
    private final org.dyh.learnhub.mapper.CodeSnippetMapper snippetMapper;
    private final org.dyh.learnhub.mapper.CodeRepoMapper repoMapper;

    /**
     * 单文件上限：超过就当"不是给人读的源码"（生成的大文件、数据文件）。
     * <p>从 256KB 提到 2MB：真实仓库里有少量几 MB 的巨源文件（生成的 parser、大常量表），
     * 256KB 一刀切会把它们误伤；2MB 之上基本可以确定是生成物或数据。
     */
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    /**
     * 单次导入的文件数上限。
     * <p>从 800 提到 2000：大仓库一次几万个文件不现实，但 2000 已能覆盖"单个模块/子系统"的规模；
     * 更大的仓库由前端**分批**发送（每批 150 个文件 / 6MB），每批都记账，最后合并成一个总账。
     */
    private static final int MAX_FILES = 2000;
    /** 单次导入的总字节上限（前端按 6MB 分批，留足余量） */
    private static final int MAX_TOTAL_BYTES = 24 * 1024 * 1024;

    /** 认得出的源码扩展名 → 语言名 */
    private static final Map<String, String> EXT_LANG = new LinkedHashMap<>();

    static {
        EXT_LANG.put("java", "java");
        EXT_LANG.put("kt", "kotlin");
        EXT_LANG.put("scala", "scala");
        EXT_LANG.put("groovy", "groovy");
        EXT_LANG.put("py", "python");
        EXT_LANG.put("rb", "ruby");
        EXT_LANG.put("php", "php");
        EXT_LANG.put("js", "js");
        EXT_LANG.put("mjs", "js");
        EXT_LANG.put("cjs", "js");
        EXT_LANG.put("jsx", "jsx");
        EXT_LANG.put("ts", "ts");
        EXT_LANG.put("tsx", "tsx");
        EXT_LANG.put("vue", "vue");
        EXT_LANG.put("svelte", "svelte");
        EXT_LANG.put("go", "go");
        EXT_LANG.put("rs", "rust");
        EXT_LANG.put("c", "c");
        EXT_LANG.put("h", "c");
        EXT_LANG.put("cpp", "cpp");
        EXT_LANG.put("cc", "cpp");
        EXT_LANG.put("hpp", "cpp");
        EXT_LANG.put("cs", "csharp");
        EXT_LANG.put("swift", "swift");
        EXT_LANG.put("dart", "dart");
        EXT_LANG.put("lua", "lua");
        EXT_LANG.put("r", "r");
        EXT_LANG.put("m", "objc");
        EXT_LANG.put("pl", "perl");
        EXT_LANG.put("sh", "shell");
        EXT_LANG.put("bash", "shell");
        EXT_LANG.put("zsh", "shell");
        EXT_LANG.put("ps1", "powershell");
        EXT_LANG.put("bat", "batch");
        EXT_LANG.put("cmd", "batch");
        EXT_LANG.put("sql", "sql");
        EXT_LANG.put("yml", "yaml");
        EXT_LANG.put("yaml", "yaml");
        EXT_LANG.put("toml", "toml");
        EXT_LANG.put("ini", "ini");
        EXT_LANG.put("conf", "conf");
        EXT_LANG.put("properties", "properties");
        EXT_LANG.put("xml", "xml");
        EXT_LANG.put("json", "json");
        EXT_LANG.put("html", "html");
        EXT_LANG.put("htm", "html");
        EXT_LANG.put("css", "css");
        EXT_LANG.put("scss", "scss");
        EXT_LANG.put("less", "less");
        EXT_LANG.put("md", "markdown");
        EXT_LANG.put("proto", "proto");
        EXT_LANG.put("gradle", "gradle");
        EXT_LANG.put("dockerfile", "dockerfile");
        EXT_LANG.put("tf", "terraform");
    }

    /** 无扩展名的特殊文件（按文件名认） */
    private static final Map<String, String> NAME_LANG = Map.of(
            "dockerfile", "dockerfile",
            "makefile", "makefile",
            "jenkinsfile", "groovy",
            ".gitignore", "text",
            "requirements.txt", "text",
            "go.mod", "text");

    /** 目录名命中即整目录跳过（依赖/构建产物/缓存） */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "bower_components", "vendor", "third_party",
            "target", "build", "dist", "out", "bin", "obj", "coverage", "test-results",
            ".idea", ".vscode", ".gradle", ".mvn", ".next", ".nuxt", ".cache", ".pytest_cache",
            "venv", ".venv", "env", "__pycache__", "site-packages", ".tox", ".mypy_cache",
            "logs", "tmp", "temp", ".terraform", ".serverless");

    /** 文件名/后缀命中即跳过（锁文件、压缩产物、二进制） */
    private static final Set<String> SKIP_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock", "poetry.lock",
            "cargo.lock", "gemfile.lock", "bun.lockb", ".ds_store", "thumbs.db");

    private static final Set<String> SKIP_EXT = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svgz", "tiff",
            "woff", "woff2", "ttf", "otf", "eot",
            "zip", "gz", "tar", "rar", "7z", "bz2", "xz", "jar", "war", "class", "apk", "exe", "dll", "so", "dylib",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "mp3", "mp4", "avi", "mov", "wav", "flac",
            "bin", "dat", "db", "sqlite", "sqlite3", "pdb", "o", "a", "lib", "obj", "pyc", "pyo", "map");

    /** 一次导入的结果（如实记账：进来了多少、跳过了多少、为什么） */
    public record Imported(int created, int updated, int skipped, int bytes, List<Map<String, Object>> langs,
                           Map<String, Integer> skipReasons, List<String> skipSamples) {
    }

    /**
     * 导入一个文件夹。
     *
     * @param projectName 项目名（前端传文件夹名）；不存在则创建
     * @param files       相对路径 + 文本内容（前端读文件文本；二进制由这里按扩展名挡掉）
     * @param existingRepoId 指定的已有项目 id（可空）
     */
    public Imported importFolder(String projectName, Long existingRepoId, List<Map<String, Object>> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("没有可导入的文件");
        }
        Long repoId = existingRepoId;
        if (repoId == null && StringUtils.hasText(projectName)) {
            CodeRepo repo = repoMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<CodeRepo>lambdaQuery()
                    .eq(CodeRepo::getName, projectName.trim()).last("LIMIT 1"));
            if (repo == null) {
                repo = new CodeRepo();
                repo.setName(projectName.trim());
                repo.setNote("由文件夹导入");
                repoMapper.insert(repo);
            }
            repoId = repo.getId();
        }

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int bytes = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        Map<String, Integer> langCount = new LinkedHashMap<>();

        for (Map<String, Object> f : files) {
            if (created + updated >= MAX_FILES) {
                skipped++;
                bump(reasons, "超过单次文件数上限（" + MAX_FILES + "）");
                break;
            }
            String path = normalizePath(str(f.get("path")));
            String text = strRaw(f.get("text"));
            if (!StringUtils.hasText(path)) {
                skipped++;
                bump(reasons, "缺少路径");
                continue;
            }
            String reason = reject(path, text);
            if (reason != null) {
                skipped++;
                bump(reasons, reason);
                if (samples.size() < 12) {
                    samples.add(path + "  ← " + reason);
                }
                continue;
            }
            int size = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_TOTAL_BYTES) {
                skipped++;
                bump(reasons, "超过单次总大小上限（" + (MAX_TOTAL_BYTES / 1024 / 1024) + "MB）");
                break;
            }
            bytes += size;

            String lang = detectLang(path, text);
            // 同一项目里同路径视为**同一份文件**：再次导入是更新，不是新增（否则重复导入会翻倍）
            CodeSnippet exist = repoId == null
                    ? snippetMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<CodeSnippet>lambdaQuery()
                            .eq(CodeSnippet::getFilePath, path).isNull(CodeSnippet::getRepoId).last("LIMIT 1"))
                    : snippetMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<CodeSnippet>lambdaQuery()
                            .eq(CodeSnippet::getFilePath, path).eq(CodeSnippet::getRepoId, repoId).last("LIMIT 1"));
            Map<String, Object> body = new LinkedHashMap<>();
            // 标题用**相对项目根**的路径：项目名已经在项目层显示过了，每行再重复一遍
            // 会让列表变成一条条长路径（实测导入真实仓库后 179 行几乎无法扫读）。
            // filePath 仍保留完整相对路径，用于定位与去重。
            body.put("title", stripProjectRoot(path, projectName));
            body.put("lang", lang);
            body.put("code", text);
            body.put("filePath", path);
            body.put("repoId", repoId);
            if (exist != null) {
                library.saveSnippet(exist.getId(), body);
                updated++;
            } else {
                body.put("explainText", "");
                library.saveSnippet(null, body);
                created++;
            }
            langCount.merge(lang, 1, Integer::sum);
        }

        List<Map<String, Object>> langs = new ArrayList<>();
        langCount.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("lang", e.getKey());
                    m.put("count", e.getValue());
                    langs.add(m);
                });
        log.info("文件夹导入：新增 {} / 更新 {} / 跳过 {}（{} 字节），项目 id={}", created, updated, skipped, bytes, repoId);
        return new Imported(created, updated, skipped, bytes, langs, reasons, samples);
    }

    /**
     * 判定是否拒绝这个文件；返回 null = 收下，否则返回**人话原因**。
     * <p>顺序按"最省事、最常见"排：路径噪声 → 文件名 → 扩展名 → 大小 → 内容像不像文本。
     */
    private String reject(String path, String text) {
        String lower = path.toLowerCase(Locale.ROOT);
        // ① 目录噪声：命中即整文件跳过（依赖/构建产物/缓存）
        for (String dir : SKIP_DIRS) {
            if (lower.startsWith(dir + "/") || lower.contains("/" + dir + "/")) {
                return "在忽略目录 " + dir + "/ 里";
            }
        }
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        // ② 文件名噪声
        if (SKIP_FILES.contains(base)) {
            return "锁文件/系统文件";
        }
        if (base.endsWith(".min.js") || base.endsWith(".min.css") || base.endsWith(".bundle.js")) {
            return "压缩后的产物";
        }
        if (base.endsWith(".map")) {
            return "sourcemap";
        }
        // ③ 扩展名噪声（二进制资源）
        String ext = extOf(base);
        if (SKIP_EXT.contains(ext)) {
            return "二进制/资源文件";
        }
        // ④ 大小
        if (text == null) {
            return "读不到文本内容";
        }
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
            return "超过单文件上限 " + (MAX_FILE_BYTES / 1024) + "KB";
        }
        if (text.isBlank()) {
            return "空文件";
        }
        // ⑤ 内容像不像文本：出现 NUL 字节基本就是二进制（前端按扩展名漏过来的兜底）
        int probe = Math.min(text.length(), 4000);
        for (int i = 0; i < probe; i++) {
            if (text.charAt(i) == 0) {
                return "内容含二进制字节";
            }
        }
        return null;
    }

    /**
     * 认语言：① 扩展名表 → ② 特殊文件名 → ③ 内容启发（兜底）。
     * <p>第③条是"自动识别"的兜底：扩展名不认识时，看有没有典型的代码特征。
     * 命中就给 {@code guessed-*}，让用户一眼看出这是猜的，而不是假装确定。
     */
    private String detectLang(String path, String text) {
        String base = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String ext = extOf(base);
        if (EXT_LANG.containsKey(ext)) {
            return EXT_LANG.get(ext);
        }
        if (NAME_LANG.containsKey(base)) {
            return NAME_LANG.get(base);
        }
        return looksLikeCode(text) ? "guessed" : "text";
    }

    /** 内容启发：缩进/分号/括号/常见关键字齐了就认为像代码（不求准，只求别漏明显的源码文件） */
    private static boolean looksLikeCode(String text) {
        String t = text.length() > 4000 ? text.substring(0, 4000) : text;
        int signals = 0;
        if (t.contains(";") || t.contains("{")) {
            signals++;
        }
        if (t.contains("(") && t.contains(")")) {
            signals++;
        }
        if (t.contains("\n    ") || t.contains("\n\t") || t.contains("\n  ")) {
            signals++;
        }
        String low = t.toLowerCase(Locale.ROOT);
        for (String kw : List.of("function ", "class ", "def ", "import ", "public ", "private ", "return ", "const ")) {
            if (low.contains(kw)) {
                signals += 2;
                break;
            }
        }
        return signals >= 3;
    }

/** 去掉路径开头的项目文件夹名（"proj/src/a.py" → "src/a.py"）；不像项目名就原样返回 */
    private static String stripProjectRoot(String path, String projectName) {
        if (!StringUtils.hasText(path) || !StringUtils.hasText(projectName)) {
            return path;
        }
        String prefix = projectName.trim() + "/";
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }
    private static String normalizePath(String p) {
        if (!StringUtils.hasText(p)) {
            return null;
        }
        String s = p.replace('\\', '/').trim();
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        return s.startsWith("/") ? s.substring(1) : s;
    }

    private static String extOf(String base) {
        int dot = base.lastIndexOf('.');
        return dot < 0 || dot == base.length() - 1 ? "" : base.substring(dot + 1);
    }

    private static void bump(Map<String, Integer> m, String key) {
        m.merge(key, 1, Integer::sum);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String strRaw(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 供前端在选文件夹时预筛（避免把几十 MB 二进制传到后端） */
    public static Set<String> knownExtensions() {
        return new LinkedHashSet<>(EXT_LANG.keySet());
    }
}
