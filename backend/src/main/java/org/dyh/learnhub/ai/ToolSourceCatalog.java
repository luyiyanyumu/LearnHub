package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A bounded, read-only view of implementation capabilities; never a general filesystem reader. */
@Service
public class ToolSourceCatalog {
    static final String SNAPSHOT_RESOURCE = "agent/source-catalog.json";
    private static final int MAX_SCAN_FILES = 500;
    private static final int MAX_FILE_BYTES = 384 * 1024;
    private static final int MAX_SCAN_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SNAPSHOT_BYTES = 12 * 1024 * 1024;
    private static final int MAX_FILES = 8;
    private static final int MAX_EXCERPT_CHARS = 4_000;
    private static final int MAX_TOTAL_CHARS = 20_000;
    private static final int MAX_TOOL_CHARS = 40_000;
    private static final Set<String> EXCLUDED_SEGMENTS = Set.of("config", "configuration", "secrets", "credentials",
            "node_modules", "target", "dist", "output", "uploads", "upload", "data", "db", "backup", "backups",
            "fixtures", "__fixtures__", ".git");
    private static final Pattern SENSITIVE_FILENAME = Pattern.compile(
            "(?i)(^|[-_.])(credentials?|secrets?|passwords?|private[-_]?keys?|database|backup)([-_.]|$)");
    private static final Pattern WORDS = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{1,63}|[\\p{IsHan}]{2,24}");
    private static final Pattern LITERAL_SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|private[_-]?key|authorization)\\s*[\"']?\\s*[:=]\\s*[\"'])([^\"'\\r\\n]+)([\"'])");
    private static final Pattern TOKEN = Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{16,}\\b|(?i:Bearer) [A-Za-z0-9._-]{16,}");
    private static final Pattern URI_PASSWORD = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s/@:]+:)[^\\s/@]+(@)");
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?-----END [^-]*PRIVATE KEY-----");
    private final ObjectMapper json = new ObjectMapper();
    private final Path configuredRoot;
    private final Path discoveryStart;
    private final boolean explicitRoot;
    private final Supplier<InputStream> snapshot;
    private final ClassLoader resourceLoader;

    @Autowired
    public ToolSourceCatalog(@Value("${learnhub.agent.source-root:}") String sourceRoot) {
        this(parseRoot(sourceRoot), Path.of("").toAbsolutePath(), sourceRoot != null && !sourceRoot.isBlank(),
                () -> ToolSourceCatalog.class.getClassLoader().getResourceAsStream(SNAPSHOT_RESOURCE));
    }

    /** Explicit project root, convenient for deterministic use outside a Spring context. */
    public ToolSourceCatalog(Path sourceRoot) {
        this(sourceRoot, null, true,
                () -> ToolSourceCatalog.class.getClassLoader().getResourceAsStream(SNAPSHOT_RESOURCE));
    }

    ToolSourceCatalog(Path sourceRoot, Path discoveryStart, boolean explicitRoot, Supplier<InputStream> snapshot) {
        this(sourceRoot, discoveryStart, explicitRoot, snapshot, ToolSourceCatalog.class.getClassLoader());
    }

    ToolSourceCatalog(Path sourceRoot, Path discoveryStart, boolean explicitRoot, Supplier<InputStream> snapshot,
                      ClassLoader resourceLoader) {
        this.configuredRoot = sourceRoot;
        this.discoveryStart = discoveryStart;
        this.explicitRoot = explicitRoot;
        this.snapshot = snapshot;
        this.resourceLoader = resourceLoader;
    }

    /** Source is evidence to design a tool, not authorization to run code or access user documents. */
    public Map<String, Object> inspect(String query, List<Map<String, Object>> toolDefinitions) {
        String boundedQuery = truncate(query == null ? "" : query.trim(), 1_000);
        List<String> warnings = new ArrayList<>();
        List<SourceFile> source = List.of();
        String mode = "unavailable";
        Path root = resolveRoot();
        if (root != null) {
            source = readCheckout(root, warnings);
            if (!source.isEmpty()) mode = "checkout";
        }
        if (source.isEmpty()) {
            source = readSnapshot(warnings);
            if (source.isEmpty()) source = readEmbeddedSource(warnings);
            if (!source.isEmpty()) mode = "bundled";
        }
        if (source.isEmpty()) warnings.add("项目源码目录与内置源码快照均不可用；只能核对当前工具定义，不能声称已检查项目实现。");
        else if ("bundled".equals(mode)) warnings.add("使用构建时内置源码快照；部署后的源码变更须重新构建快照才能反映。");

        List<Map<String, Object>> tools = toolCapabilities(toolDefinitions);
        Set<String> terms = terms(boundedQuery);
        List<Map<String, Object>> relevantTools = tools.stream()
                .filter(tool -> score(String.valueOf(tool.get("name")), String.valueOf(tool.get("description")), terms) > 0)
                .sorted(Comparator.<Map<String, Object>>comparingInt(tool ->
                        score(String.valueOf(tool.get("name")), String.valueOf(tool.get("description")), terms)).reversed())
                .limit(12).toList();
        for (Map<String, Object> tool : relevantTools) terms.addAll(terms(String.valueOf(tool.get("name"))));

        List<SourceFile> ranked = source.stream().sorted(Comparator
                .<SourceFile>comparingInt(file -> sourceScore(file, terms)).reversed()
                .thenComparing(SourceFile::path)).toList();
        List<Map<String, Object>> excerpts = new ArrayList<>();
        int remaining = MAX_TOTAL_CHARS;
        for (SourceFile file : ranked) {
            int relevance = sourceScore(file, terms);
            // With a specific question avoid presenting unrelated implementation as relevant evidence.
            if (!terms.isEmpty() && relevance == 0 && !isMetadata(file.path())) continue;
            if (excerpts.size() == MAX_FILES || remaining <= 0) break;
            Map<String, Object> excerpt = excerpt(file, terms, Math.min(MAX_EXCERPT_CHARS, remaining));
            excerpt.put("score", relevance);
            excerpts.add(excerpt);
            remaining -= String.valueOf(excerpt.get("excerpt")).length();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("readOnly", true);
        result.put("available", !source.isEmpty());
        result.put("sourceMode", mode);
        result.put("query", redact(boundedQuery));
        result.put("sourceRevision", revision(source));
        result.put("scannedFiles", source.size());
        result.put("files", excerpts);
        result.put("toolCapabilities", tools);
        result.put("relatedTools", relevantTools);
        result.put("warnings", warnings.stream().distinct().limit(8).toList());
        return result;
    }

    private Path resolveRoot() {
        if (explicitRoot) return validRoot(configuredRoot);
        Path current = discoveryStart;
        for (int parents = 0; current != null && parents <= 4; parents++, current = current.getParent()) {
            Path root = validRoot(current);
            if (root != null) return root;
        }
        return null;
    }

    private static Path parseRoot(String configured) {
        if (configured == null || configured.isBlank()) return null;
        try { return Path.of(configured.trim()); } catch (RuntimeException ignored) { return null; }
    }

    private static Path validRoot(Path candidate) {
        if (candidate == null) return null;
        try {
            Path real = candidate.toRealPath();
            if (Files.isDirectory(real.resolve("backend/src/main/java"), LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(real.resolve("frontend/src"), LinkOption.NOFOLLOW_LINKS)) return real;
            // A backend process is also commonly started from the backend module directory.
            if ("backend".equalsIgnoreCase(String.valueOf(real.getFileName())) && real.getParent() != null)
                return validRoot(real.getParent());
        } catch (IOException | SecurityException ignored) { }
        return null;
    }

    private List<SourceFile> readCheckout(Path root, List<String> warnings) {
        List<Path> candidates = new ArrayList<>();
        for (String metadata : List.of("backend/pom.xml", "frontend/package.json", "backend/src/main/resources/note-edit-tool.json")) {
            Path file = root.resolve(metadata);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) candidates.add(file);
        }
        for (String area : List.of("backend/src/main/java", "frontend/src", "backend/src/main/resources/agent")) {
            Path start = root.resolve(area);
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.walkFileTree(start, Set.of(), 16, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (candidates.size() >= MAX_SCAN_FILES) return FileVisitResult.TERMINATE;
                        return excluded(root.relativize(dir).toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && !attrs.isSymbolicLink() && allowed(relative(root, file))) candidates.add(file);
                        return candidates.size() >= MAX_SCAN_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, IOException error) { return FileVisitResult.CONTINUE; }
                });
            } catch (IOException | SecurityException ignored) { warnings.add("部分源码目录不可读取，已跳过。"); }
        }
        candidates.sort(Comparator.comparing(path -> relative(root, path)));
        List<SourceFile> files = new ArrayList<>();
        int readBytes = 0;
        boolean limited = candidates.size() >= MAX_SCAN_FILES;
        for (Path candidate : candidates) {
            try {
                if (Files.isSymbolicLink(candidate) || !candidate.toRealPath().startsWith(root)) continue;
                long size = Files.size(candidate);
                if (size > MAX_FILE_BYTES || readBytes + size > MAX_SCAN_BYTES) { limited = true; continue; }
                try (InputStream input = Files.newInputStream(candidate)) {
                    byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                    if (bytes.length > MAX_FILE_BYTES || readBytes + bytes.length > MAX_SCAN_BYTES) { limited = true; continue; }
                    readBytes += bytes.length;
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    if (content.indexOf('\0') >= 0) continue;
                    files.add(new SourceFile(relative(root, candidate), redact(content), digest(bytes)));
                }
            } catch (IOException | SecurityException ignored) { warnings.add("部分源码文件不可读取，已跳过。"); }
        }
        if (limited) warnings.add("源码目录超过读取预算，返回内容为限量相关片段。");
        return List.copyOf(files);
    }

    private List<SourceFile> readSnapshot(List<String> warnings) {
        try (InputStream input = snapshot == null ? null : snapshot.get()) {
            if (input == null) return List.of();
            byte[] bytes = input.readNBytes(MAX_SNAPSHOT_BYTES + 1);
            if (bytes.length > MAX_SNAPSHOT_BYTES) { warnings.add("内置源码快照超过读取预算，已跳过。"); return List.of(); }
            JsonNode document = json.readTree(bytes);
            if (!document.path("files").isArray() || document.path("version").asInt() != 1) {
                warnings.add("内置源码快照格式不可用，已跳过。"); return List.of();
            }
            List<SourceFile> files = new ArrayList<>();
            Set<String> paths = new LinkedHashSet<>();
            int total = 0;
            for (JsonNode entry : document.path("files")) {
                String path = entry.path("path").asText("");
                String content = entry.path("content").asText("");
                if (!allowed(path) || !paths.add(path) || content.indexOf('\0') >= 0) continue;
                byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
                if (contentBytes.length > MAX_FILE_BYTES || total + contentBytes.length > MAX_SCAN_BYTES) continue;
                String hash = digest(contentBytes);
                String suppliedHash = entry.path("sha256").asText("");
                if (!suppliedHash.isBlank() && !hash.equalsIgnoreCase(suppliedHash)) {
                    warnings.add("内置源码快照有文件校验不匹配，已跳过。"); continue;
                }
                files.add(new SourceFile(path, redact(content), hash));
                total += contentBytes.length;
                if (files.size() >= MAX_SCAN_FILES) break;
            }
            return List.copyOf(files);
        } catch (IOException | RuntimeException ignored) {
            warnings.add("内置源码快照无法读取，已跳过。"); return List.of();
        }
    }

    /** Maven copies only the allowed source areas here; the same checks still apply at runtime. */
    private List<SourceFile> readEmbeddedSource(List<String> warnings) {
        final String prefix = "agent/project-source/";
        var resolver = new PathMatchingResourcePatternResolver(resourceLoader);
        List<SourceFile> files = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        int totalBytes = 0;
        try {
            List<Resource> candidates = new ArrayList<>();
            for (String pattern : List.of("backend/src/main/java/**/*.java", "frontend/src/**/*.vue",
                    "frontend/src/**/*.js", "frontend/src/**/*.ts", "frontend/src/**/*.css",
                    "backend/pom.xml", "frontend/package.json", "backend/src/main/resources/note-edit-tool.json",
                    "backend/src/main/resources/agent/tool-schema*.json", "backend/src/main/resources/agent/*-tool.json")) {
                for (Resource resource : resolver.getResources("classpath*:" + prefix + pattern)) {
                    if (candidates.size() >= MAX_SCAN_FILES) break;
                    candidates.add(resource);
                }
                if (candidates.size() >= MAX_SCAN_FILES) break;
            }
            for (Resource resource : candidates) {
                String location = resource.getURL().toExternalForm();
                int at = location.lastIndexOf(prefix);
                if (at < 0) continue;
                String path = java.net.URLDecoder.decode(location.substring(at + prefix.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
                if (!allowed(path) || !paths.add(path)) continue;
                try (InputStream input = resource.getInputStream()) {
                    byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                    if (bytes.length > MAX_FILE_BYTES || totalBytes + bytes.length > MAX_SCAN_BYTES) continue;
                    String content = new String(bytes, StandardCharsets.UTF_8);
                    if (content.indexOf('\0') >= 0) continue;
                    files.add(new SourceFile(path, redact(content), digest(bytes)));
                    totalBytes += bytes.length;
                } catch (IOException ignored) { warnings.add("部分内置源码文件不可读取，已跳过。"); }
            }
        } catch (IOException | RuntimeException ignored) { warnings.add("内置源码目录无法读取，已跳过。"); }
        return List.copyOf(files);
    }

    private List<Map<String, Object>> toolCapabilities(List<Map<String, Object>> definitions) {
        if (definitions == null) return List.of();
        List<Map<String, Object>> tools = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int capacity = Math.min(256, definitions.size());
        // Reserve a capability name and description for every tool before spending budget on detailed schemas.
        // Otherwise a few large schemas could hide a later, already available tool from duplication review.
        int descriptionLimit = Math.max(0, Math.min(600, (MAX_TOOL_CHARS / 2) / Math.max(1, capacity) - 64));
        int chars = 0;
        for (Map<String, Object> definition : definitions) {
            if (tools.size() >= 256) break;
            if (definition == null) continue;
            Object nested = definition.get("function");
            Map<?, ?> function = nested instanceof Map<?, ?> map ? map : definition;
            String name = truncate(String.valueOf(function.getOrDefault("name", null)), 100);
            if (!name.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || !seen.add(name)) continue;
            String description = truncate(redact(String.valueOf(function.getOrDefault("description", null))), descriptionLimit);
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", name);
            tool.put("description", "null".equals(description) ? "" : description);
            Object parameters = function.get("parameters");
            if (parameters instanceof Map<?, ?> schema && chars < MAX_TOOL_CHARS) {
                try {
                    String serialized = redact(json.writeValueAsString(schema));
                    int reserved = (capacity - tools.size()) * (64 + descriptionLimit);
                    if (serialized.length() <= 4_000 && chars + serialized.length() + reserved <= MAX_TOOL_CHARS) {
                        tool.put("parameters", json.readValue(serialized, Map.class));
                        chars += serialized.length();
                    } else {
                        Object properties = schema.get("properties");
                        tool.put("parameterNames", properties instanceof Map<?, ?> props
                                ? props.keySet().stream().limit(100).map(String::valueOf).toList() : List.of());
                        tool.put("schemaOmitted", true);
                    }
                } catch (IOException | RuntimeException ignored) { tool.put("schemaOmitted", true); }
            }
            tools.add(tool);
            chars += name.length() + description.length();
        }
        return List.copyOf(tools);
    }

    private static boolean allowed(String path) {
        if (path == null || path.isBlank() || path.indexOf('\\') >= 0 || path.startsWith("/")
                || path.indexOf(':') >= 0 || path.contains("//") || excluded(path)) return false;
        for (String part : path.split("/")) if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
        if (isMetadata(path) || "backend/src/main/resources/note-edit-tool.json".equals(path)) return true;
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.startsWith("backend/src/main/java/")) return lower.endsWith(".java");
        if (path.startsWith("frontend/src/")) return Set.of("js", "ts", "vue", "css").stream().anyMatch(ext -> lower.endsWith("." + ext));
        return path.startsWith("backend/src/main/resources/agent/") && lower.endsWith(".json")
                && (lower.substring(lower.lastIndexOf('/') + 1).startsWith("tool-schema") || lower.endsWith("-tool.json"));
    }

    private static boolean excluded(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String part : normalized.split("/")) {
            if (part.startsWith(".") || EXCLUDED_SEGMENTS.contains(part) || SENSITIVE_FILENAME.matcher(part).find()) return true;
        }
        return false;
    }

    private static boolean isMetadata(String path) { return "backend/pom.xml".equals(path) || "frontend/package.json".equals(path); }
    private static String relative(Path root, Path path) { return root.relativize(path).toString().replace('\\', '/'); }

    private static Set<String> terms(String query) {
        Set<String> result = new LinkedHashSet<>();
        var words = WORDS.matcher(query.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT));
        while (words.find() && result.size() < 32) {
            String term = words.group();
            result.add(term);
            for (String piece : term.split("_")) if (piece.length() >= 2) result.add(piece);
        }
        // Chinese requirements can still locate English-named implementations.
        for (String[] alias : List.of(new String[]{"笔记", "note"}, new String[]{"资料", "file"},
                new String[]{"文件夹", "folder"}, new String[]{"目录", "toc"}, new String[]{"公式", "math"},
                new String[]{"检索", "search", "retrieval"}, new String[]{"工具", "tool"},
                new String[]{"图谱", "graph"}, new String[]{"表格", "table"}, new String[]{"光标", "caret", "selection"})) {
            if (query.contains(alias[0])) for (int i = 1; i < alias.length; i++) result.add(alias[i]);
        }
        return result;
    }

    private static int score(String name, String text, Set<String> terms) {
        String lowerName = name.toLowerCase(Locale.ROOT), lowerText = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lowerName.contains(term)) score += 12;
            int start = 0, count = 0;
            while ((start = lowerText.indexOf(term, start)) >= 0 && count < 4) { count++; start += term.length(); }
            score += count;
        }
        return score;
    }
    private static int sourceScore(SourceFile file, Set<String> terms) {
        int score = score(file.path(), file.content(), terms);
        // The dispatcher is useful scaffolding even if the requested capability has no known keyword yet.
        return score + (file.path().endsWith("/ai/AgentService.java") ? 3 : 0);
    }

    private static Map<String, Object> excerpt(SourceFile file, Set<String> terms, int budget) {
        String[] lines = file.content().split("\\R", -1);
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) candidates.add(i);
        candidates.sort(Comparator.<Integer>comparingInt(line -> score("", lines[line], terms)).reversed().thenComparingInt(Integer::intValue));
        Set<Integer> chosen = new LinkedHashSet<>();
        int windows = 0;
        for (int center : candidates) {
            if (windows > 0 && score("", lines[center], terms) == 0) break;
            if (chosen.contains(center)) continue;
            for (int i = Math.max(0, center - 8); i <= Math.min(lines.length - 1, center + 10); i++) chosen.add(i);
            if (++windows >= 3) break;
        }
        StringBuilder text = new StringBuilder();
        List<Map<String, Integer>> ranges = new ArrayList<>();
        int startLine = -1, previous = -2;
        for (int line : chosen.stream().sorted().toList()) {
            if (text.length() >= budget) break;
            if (line != previous + 1) {
                if (startLine >= 0) ranges.add(Map.of("startLine", startLine + 1, "endLine", previous + 1));
                if (!text.isEmpty()) text.append("\n…\n");
                startLine = line;
            }
            text.append(lines[line]).append('\n');
            previous = line;
        }
        if (startLine >= 0) ranges.add(Map.of("startLine", startLine + 1, "endLine", previous + 1));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", file.path());
        result.put("sha256", file.sha256());
        result.put("startLine", ranges.isEmpty() ? 1 : ranges.getFirst().get("startLine"));
        result.put("endLine", ranges.isEmpty() ? 1 : ranges.getLast().get("endLine"));
        result.put("lineRanges", ranges);
        result.put("totalLines", lines.length);
        result.put("excerpt", truncate(text.toString(), budget));
        result.put("truncated", chosen.size() < lines.length || text.length() > budget);
        return result;
    }

    private static String redact(String value) {
        String text = PRIVATE_KEY.matcher(value).replaceAll(match -> "[REDACTED PRIVATE KEY]"
                + "\n".repeat((int) match.group().chars().filter(character -> character == '\n').count()));
        text = LITERAL_SECRET.matcher(text).replaceAll("$1[REDACTED]$3");
        text = TOKEN.matcher(text).replaceAll("[REDACTED TOKEN]");
        return URI_PASSWORD.matcher(text).replaceAll("$1[REDACTED]$2");
    }

    private static String revision(List<SourceFile> source) {
        if (source.isEmpty()) return "";
        String manifest = source.stream().sorted(Comparator.comparing(SourceFile::path))
                .map(file -> file.path() + ":" + file.sha256()).reduce("", (a, b) -> a + b + "\n");
        return digest(manifest.getBytes(StandardCharsets.UTF_8));
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static String truncate(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
    private record SourceFile(String path, String content, String sha256) { }
}
