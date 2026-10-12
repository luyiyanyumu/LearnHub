package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolSourceCatalogTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test void findsRelevantImplementationsAndReportsStableSourceHashes() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        String body = "package example;\nclass FolderService {\n  void move_note(String folder) {}\n}\n";
        write(root, "backend/src/main/java/example/FolderService.java", body);
        write(root, "frontend/src/NoteList.vue", "<template>Folder note</template>");
        write(root, "backend/pom.xml", "<project><artifactId>synthetic</artifactId></project>");
        write(root, "frontend/package.json", "{\"name\":\"synthetic\"}");
        var catalog = checkout(root);
        var result = catalog.inspect("把笔记移动到文件夹 move_note", tools());
        assertEquals(true, result.get("available"));
        assertEquals("checkout", result.get("sourceMode"));
        assertEquals(true, result.get("readOnly"));
        var source = files(result).stream().filter(file -> String.valueOf(file.get("path")).endsWith("FolderService.java")).findFirst().orElseThrow();
        assertEquals(hash(body), source.get("sha256"));
        assertTrue(String.valueOf(source.get("excerpt")).contains("move_note"));
        assertEquals(result.get("sourceRevision"), catalog.inspect("different query", tools()).get("sourceRevision"));
        assertFalse(json.writeValueAsString(result).contains(root.toString()));
        assertEquals("move_note", capabilities(result, "relatedTools").getFirst().get("name"));
        write(root, "backend/src/main/java/example/FolderService.java", body.replace("void", "public void"));
        assertNotEquals(result.get("sourceRevision"), catalog.inspect("move_note", tools()).get("sourceRevision"));
    }

    @Test void neverReadsConfigurationDataOrHiddenSourceFixtures() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        write(root, "backend/src/main/java/example/NoteService.java", "class NoteService { void read_note() {} }");
        for (String path : List.of("backend/src/main/resources/application.yml", "backend/src/main/resources/schema.sql",
                "backend/src/main/java/config/SecretSettings.java", "backend/src/main/java/.cache/Note.java",
                "frontend/src/data/private.js", "frontend/src/credentials.js", "frontend/src/fixtures/Note.js",
                "output/Note.java", "uploads/Note.java", "frontend/src/.env")) write(root, path, "SYNTHETIC_PRIVATE_CONTENT");
        var result = checkout(root).inspect("note", tools());
        assertEquals(1, result.get("scannedFiles"));
        assertFalse(json.writeValueAsString(result).contains("SYNTHETIC_PRIVATE_CONTENT"));
    }

    @Test void skipsLinksOutsideTheCheckoutWhenThePlatformSupportsThem() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        write(root, "frontend/src/Note.js", "export const note = true;");
        Path external = temporary.resolve("outside/Leak.java");
        Files.createDirectories(external.getParent());
        Files.writeString(external, "OUTSIDE_SYNTHETIC_VALUE");
        try { Files.createSymbolicLink(root.resolve("frontend/src/Leak.js"), external); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            // Windows can require Developer Mode for creating a link; equivalent traversal is always tested below.
            return;
        }
        var result = checkout(root).inspect("note leak", tools());
        assertEquals(1, result.get("scannedFiles"));
        assertFalse(json.writeValueAsString(result).contains("OUTSIDE_SYNTHETIC_VALUE"));
    }

    @Test void redactsLiteralSecretsButKeepsHashesAndLineNumbersForSourceAudit() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        String source = "class NoteClient {\n  String apiKey = \"synthetic-secret-key\";\n"
                + "  String password = \"synthetic-password\";\n  String token = \"sk-proj-abcdefghijklmnopqrstuvwx\";\n"
                + "  String uri = \"https://example:synthetic-uri-password@host.test/path\";\n"
                + "  String pem = \"-----BEGIN PRIVATE KEY-----\nSYNTHETIC_KEY_BODY\n-----END PRIVATE KEY-----\";\n"
                + "  void read_note() {}\n}\n";
        write(root, "backend/src/main/java/example/NoteClient.java", source);
        var file = files(checkout(root).inspect("read_note", tools())).getFirst();
        String returned = String.valueOf(file.get("excerpt"));
        assertFalse(returned.contains("synthetic-secret-key"));
        assertFalse(returned.contains("synthetic-password"));
        assertFalse(returned.contains("abcdefghijklmnopqrstuvwx"));
        assertFalse(returned.contains("synthetic-uri-password"));
        assertFalse(returned.contains("SYNTHETIC_KEY_BODY"));
        assertTrue(returned.contains("[REDACTED]"));
        assertEquals(hash(source), file.get("sha256"));
        assertEquals(source.split("\\R", -1).length, file.get("totalLines"));
    }

    @Test void returnsDistinctRelevantLineWindowsInsteadOfOnlyTheFileHeader() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        StringBuilder source = new StringBuilder();
        for (int line = 1; line <= 250; line++) source.append(line == 180 ? "void move_note(String folder) {}" : "// source line " + line).append('\n');
        write(root, "backend/src/main/java/example/Folders.java", source.toString());
        var file = files(checkout(root).inspect("move_note", tools())).getFirst();
        assertTrue(String.valueOf(file.get("excerpt")).contains("void move_note"));
        assertEquals(172, file.get("startLine"));
        assertTrue((int) file.get("endLine") >= 180);
        assertEquals(true, file.get("truncated"));
    }

    @Test void rejectsOversizeFilesAndCapsReturnedSource() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        write(root, "frontend/src/Oversize.js", "x".repeat(400_000));
        for (int file = 0; file < 20; file++) write(root, "frontend/src/Note" + file + ".js", "// note operation\n".repeat(2_000));
        var result = checkout(root).inspect("note", tools());
        assertEquals(20, result.get("scannedFiles"));
        assertTrue(files(result).size() <= 8);
        assertTrue(files(result).stream().mapToInt(file -> String.valueOf(file.get("excerpt")).length()).sum() <= 20_000);
        assertTrue(files(result).stream().allMatch(file -> String.valueOf(file.get("excerpt")).length() <= 4_000));
        assertTrue(String.valueOf(result.get("warnings")).contains("预算"));
    }

    @Test void detectsParentCheckoutAndBackendWorkingDirectory() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        write(root, "backend/src/main/java/example/NoteService.java", "class NoteService {}");
        Path cwd = root.resolve("backend/work/nested");
        Files.createDirectories(cwd);
        var catalog = new ToolSourceCatalog(null, cwd, false, () -> null, emptyClassLoader());
        assertEquals("checkout", catalog.inspect("note", tools()).get("sourceMode"));
        assertEquals("checkout", checkout(root.resolve("backend")).inspect("note", tools()).get("sourceMode"));
        var explicitlyMissing = new ToolSourceCatalog(root.resolve("missing"), cwd, true, () -> null, emptyClassLoader());
        assertEquals(false, explicitlyMissing.inspect("note", tools()).get("available"));
    }

    @Test void unavailableSourceIsExplicitWhileExistingToolCapabilitiesRemainAuditable() {
        var catalog = new ToolSourceCatalog(null, null, true, () -> null, emptyClassLoader());
        var result = catalog.inspect("move_note", tools());
        assertEquals(false, result.get("available"));
        assertEquals("unavailable", result.get("sourceMode"));
        assertEquals("", result.get("sourceRevision"));
        assertTrue(files(result).isEmpty());
        assertEquals(2, capabilities(result, "toolCapabilities").size());
        assertTrue(String.valueOf(result.get("warnings")).contains("不能声称"));
    }

    @Test void usesValidatedBundledJsonAndRejectsTraversalConfigDuplicateAndHashMismatch() throws Exception {
        String content = "class NoteSource { void move_note() {} }";
        var snapshot = Map.of("version", 1, "files", List.of(
                Map.of("path", "backend/src/main/java/example/NoteSource.java", "content", content, "sha256", hash(content)),
                Map.of("path", "backend/src/main/java/example/NoteSource.java", "content", "DUPLICATE"),
                Map.of("path", "backend/src/main/java/../../Leak.java", "content", "TRAVERSAL"),
                Map.of("path", "frontend/src/config/Secrets.js", "content", "CONFIG"),
                Map.of("path", "backend/src/main/resources/application.yml", "content", "APPLICATION"),
                Map.of("path", "frontend/src/HashMismatch.js", "content", "MISMATCH", "sha256", "a".repeat(64))));
        byte[] bytes = json.writeValueAsBytes(snapshot);
        var catalog = new ToolSourceCatalog(null, null, true, () -> new ByteArrayInputStream(bytes), emptyClassLoader());
        var result = catalog.inspect("move_note", tools());
        assertEquals("bundled", result.get("sourceMode"));
        assertEquals(1, result.get("scannedFiles"));
        assertEquals(hash(content), files(result).getFirst().get("sha256"));
        String returned = json.writeValueAsString(result);
        for (String denied : List.of("TRAVERSAL", "CONFIG", "APPLICATION", "MISMATCH", "DUPLICATE")) assertFalse(returned.contains(denied));
        assertTrue(String.valueOf(result.get("warnings")).contains("校验"));
    }

    @Test void usesClasspathSourceDirectoryForPackagedRuntimeAndPreservesRevision() throws Exception {
        Path classpath = temporary.resolve("synthetic-classpath");
        String code = "class NoteHandler { void move_note() {} }";
        write(classpath, "agent/project-source/backend/src/main/java/example/NoteHandler.java", code);
        write(classpath, "agent/project-source/frontend/src/Notes.vue", "<template>note</template>");
        write(classpath, "agent/project-source/frontend/package.json", "{\"name\":\"synthetic\"}");
        write(classpath, "agent/project-source/backend/src/main/resources/application.yml", "DO_NOT_READ");
        write(classpath, "agent/project-source/frontend/src/config/Secrets.js", "DO_NOT_READ");
        try (var loader = new URLClassLoader(new java.net.URL[]{classpath.toUri().toURL()}, null)) {
            var catalog = new ToolSourceCatalog(null, null, true, () -> null, loader);
            var result = catalog.inspect("move_note", tools());
            assertEquals("bundled", result.get("sourceMode"));
            assertEquals(3, result.get("scannedFiles"));
            assertEquals(hash(code), files(result).getFirst().get("sha256"));
            assertFalse(json.writeValueAsString(result).contains("DO_NOT_READ"));
            assertEquals(result.get("sourceRevision"), catalog.inspect("note", tools()).get("sourceRevision"));
        }
    }

    @Test void schemaResourcesAreExplicitlyAllowedAndToolSchemaBudgetRetainsParameterNames() throws Exception {
        Path root = temporary.resolve("synthetic-project");
        write(root, "frontend/src/Note.js", "// note");
        write(root, "backend/src/main/resources/note-edit-tool.json", "{\"note\":true}");
        write(root, "backend/src/main/resources/agent/tool-schema-move.json", "{\"folder\":true}");
        write(root, "backend/src/main/resources/agent/unrelated.json", "DO_NOT_READ");
        Map<String, Object> oversized = Map.of("function", Map.of("name", "move_note", "description", "移动笔记",
                "parameters", Map.of("properties", Map.of("folder_id", Map.of("description", "x".repeat(8_000))))));
        var result = checkout(root).inspect("note folder", List.of(oversized, oversized));
        assertEquals(3, result.get("scannedFiles"));
        var capability = capabilities(result, "toolCapabilities").getFirst();
        assertEquals(List.of("folder_id"), capability.get("parameterNames"));
        assertEquals(true, capability.get("schemaOmitted"));
        assertEquals(1, capabilities(result, "toolCapabilities").size());
    }

    @Test void malformedSnapshotDoesNotPretendToHaveReadProjectSource() {
        var catalog = new ToolSourceCatalog(null, null, true,
                () -> new ByteArrayInputStream("{not-json".getBytes(StandardCharsets.UTF_8)), emptyClassLoader());
        var result = catalog.inspect(null, null);
        assertEquals(false, result.get("available"));
        assertTrue(capabilities(result, "toolCapabilities").isEmpty());
        assertTrue(String.valueOf(result.get("warnings")).contains("无法读取"));
    }

    @Test void largeSchemaBudgetsNeverHideLaterExistingToolNamesFromDuplicateReview() throws Exception {
        var definitions = new java.util.ArrayList<Map<String, Object>>();
        for (int tool = 0; tool < 100; tool++) definitions.add(Map.of("function", Map.of("name", "existing_tool_" + tool,
                "description", "Existing capability " + tool + " " + "x".repeat(600),
                "parameters", Map.of("properties", Map.of("target", Map.of("description", "y".repeat(3_000)))))));
        var catalog = new ToolSourceCatalog(null, null, true, () -> null, emptyClassLoader());
        var capabilities = capabilities(catalog.inspect("existing_tool_99", definitions), "toolCapabilities");
        assertEquals(100, capabilities.size());
        assertEquals("existing_tool_99", capabilities.getLast().get("name"));
        assertTrue(String.valueOf(capabilities.getLast().get("description")).contains("Existing capability 99"));
        assertTrue(json.writeValueAsString(capabilities).length() < 50_000);
    }

    private ToolSourceCatalog checkout(Path root) {
        return new ToolSourceCatalog(root, null, true, () -> null, emptyClassLoader());
    }
    private static ClassLoader emptyClassLoader() { return new URLClassLoader(new java.net.URL[0], null); }
    private static void write(Path root, String relative, String text) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static List<Map<String, Object>> tools() {
        return List.of(Map.of("type", "function", "function", Map.of("name", "move_note", "description", "移动笔记到文件夹",
                        "parameters", Map.of("type", "object", "properties", Map.of("folder_id", Map.of("type", "integer"))))),
                Map.of("name", "search_knowledge", "description", "检索相关原文", "parameters", Map.of("type", "object")));
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> files(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("files");
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> capabilities(Map<String, Object> result, String field) {
        return (List<Map<String, Object>>) result.get(field);
    }
}
