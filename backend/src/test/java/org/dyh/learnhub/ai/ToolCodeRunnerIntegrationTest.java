package org.dyh.learnhub.ai;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.entity.AppSetting;
import org.dyh.learnhub.mapper.AppSettingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real isolated Python, synthetic source and an in-memory registry: no database, model or user documents. */
@EnabledIfEnvironmentVariable(named = "LEARNHUB_TEST_CODE_RUNNER_URL", matches = ".+")
class ToolCodeRunnerIntegrationTest {
    private static final String SOURCE_PATH = "backend/src/main/java/synthetic/TextCapabilities.java";
    private static final String SOURCE_HASH = "a".repeat(64);
    private static final Set<String> WRITES = Set.of("edit_note");
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> persisted = new AtomicReference<>();
    private ToolEvolutionService registry;
    private CodeExecutionService runtime;
    private List<Object> builtins;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        AppSettingMapper mapper = mock(AppSettingMapper.class);
        ToolSourceCatalog source = mock(ToolSourceCatalog.class);
        builtins = List.of(Map.of("type", "function", "function", Map.of("name", "edit_note",
                "description", "Synthetic note change approval", "parameters", Map.of("type", "object"))));
        when(mapper.selectById(ToolEvolutionService.REGISTRY_KEY)).thenAnswer(ignored -> {
            String raw = persisted.get();
            if (raw == null) return null;
            AppSetting row = new AppSetting(); row.setSettingKey(ToolEvolutionService.REGISTRY_KEY); row.setSettingValue(raw);
            return row;
        });
        when(mapper.insert(any(AppSetting.class))).thenAnswer(invocation -> {
            AppSetting row = invocation.getArgument(0);
            if (!persisted.compareAndSet(null, row.getSettingValue())) throw new IllegalStateException("synthetic registry insert conflict");
            return 1;
        });
        when(mapper.update(any(AppSetting.class), any(Wrapper.class))).thenAnswer(invocation -> {
            AppSetting row = invocation.getArgument(0);
            QueryWrapper<AppSetting> condition = invocation.getArgument(1);
            condition.getSqlSegment();
            String previous = persisted.get();
            if (!condition.getParamNameValuePairs().containsValue(previous)) return 0;
            return persisted.compareAndSet(previous, row.getSettingValue()) ? 1 : 0;
        });
        when(source.inspect(anyString(), anyList())).thenReturn(Map.of("ok", true, "available", true,
                "sourceMode", "synthetic", "files", List.of(Map.of("path", SOURCE_PATH,
                        "sha256", SOURCE_HASH, "excerpt", "class TextCapabilities {}"))));
        runtime = new CodeExecutionService(System.getenv("LEARNHUB_TEST_CODE_RUNNER_URL"));
        assertTrue(runtime.available(), "The explicitly supplied isolated test runner must be ready");
        registry = new ToolEvolutionService(json, mapper, source, runtime);
    }

    @Test void actualPythonCanBeTestedRegisteredDeduplicatedUpgradedAndRolledBack() throws Exception {
        String name = "adaptive_synthetic_case";
        ObjectNode proposal = proposal(name, "lower");
        Map<String, Object> dryRun = registry.test(proposal, builtins, WRITES);
        assertEquals(true, dryRun.get("ok"), dryRun.toString());
        assertEquals(false, dryRun.get("registered"));
        assertEquals(2, dryRun.get("passed"));
        assertNull(persisted.get(), "Dry run must not persist the proposal or an audit entry");

        Map<String, Object> registered = registry.evolve(proposal, builtins, WRITES, "synthetic-session");
        assertEquals(true, registered.get("registered"), registered.toString());
        assertEquals(1, registered.get("version"));
        assertEquals("alpha", execute(name, " Alpha ").path("output").asText());

        ObjectNode alias = proposal("adaptive_synthetic_alias", "lower");
        ((ObjectNode) alias.path("steps").get(0)).put("source",
                "# a comment does not create a new capability\n\ndef run(input):\n    return input['text'].strip().lower()  # same AST\n");
        Map<String, Object> reuse = registry.evolve(alias, builtins, WRITES, "synthetic-session");
        assertEquals("reuse_existing", reuse.get("decision"), reuse.toString());
        assertEquals(name, reuse.get("existing_tool"));
        assertEquals(1, state().path("tools").size());

        ObjectNode duplicateCapability = proposal("adaptive_synthetic_parallel", "upper");
        Map<String, Object> rejected = registry.evolve(duplicateCapability, builtins, WRITES, "synthetic-session");
        assertEquals(false, rejected.get("ok"));
        assertTrue(rejected.get("error").toString().contains("沿用"));
        assertEquals(1, state().path("tools").size());

        ObjectNode upgrade = proposal(name, "upper").put("extends_tool", name).put("expected_version", 1);
        Map<String, Object> upgraded = registry.evolve(upgrade, builtins, WRITES, "synthetic-session");
        assertEquals(true, upgraded.get("registered"), upgraded.toString());
        assertEquals("extend_existing", upgraded.get("decision"));
        assertEquals(2, upgraded.get("version"));
        assertEquals("ALPHA", execute(name, " Alpha ").path("output").asText());

        Map<String, Object> rollback = registry.manage(name, "rollback", 2, builtins, WRITES);
        assertEquals(true, rollback.get("ok"), rollback.toString());
        assertEquals(3, rollback.get("version"), "Rollback must keep versions monotonic");
        assertEquals("alpha", execute(name, " Alpha ").path("output").asText());
        assertEquals(1, state().path("tools").size());
    }

    @Test void actualCompilerErrorsAreVisibleAndCannotRegister() throws Exception {
        ObjectNode invalid = proposal("adaptive_synthetic_invalid", "lower");
        ((ObjectNode) invalid.path("steps").get(0)).put("source", "def run(input)\n    return input['text']\n");
        JsonNode analysis = runtime.analyze(invalid.path("steps").get(0).path("source").asText());
        assertFalse(analysis.path("ok").asBoolean());
        assertEquals("code_syntax_error", analysis.path("code").asText());
        assertFalse(analysis.path("error").asText().isBlank());
        Map<String, Object> dryRun = registry.test(invalid, builtins, WRITES);
        assertEquals(false, dryRun.get("ok"));
        assertNull(persisted.get());
        Map<String, Object> registration = registry.evolve(invalid, builtins, WRITES, "synthetic-session");
        assertEquals(false, registration.get("ok"));
        assertEquals(0, state().path("tools").size());
    }

    @Test void actualCodeCanPrepareAWriteButCannotBypassNativeStaging() throws Exception {
        String name = "adaptive_synthetic_staged";
        ObjectNode proposal = proposal(name, "lower");
        ObjectNode call = proposal.withArray("steps").addObject().put("id", "write").put("op", "call").put("tool", "edit_note");
        call.putObject("arguments").put("note_id", 1).set("content", ref("steps.format"));
        proposal.set("output", ref("steps.write"));
        JsonNode staged = json.readTree("{\"ok\":true,\"staged\":true,\"action_id\":42,\"message\":\"合成待确认\"}");
        for (JsonNode item : proposal.path("tests")) {
            ObjectNode test = (ObjectNode) item;
            JsonNode expectedContent = test.get("expected").deepCopy();
            ObjectNode fixture = test.putArray("tool_results").addObject().put("tool", "edit_note");
            fixture.putObject("arguments").put("note_id", 1).set("content", expectedContent);
            fixture.set("result", staged.deepCopy());
            test.set("expected", staged.deepCopy());
        }
        Map<String, Object> registered = registry.evolve(proposal, builtins, WRITES, "synthetic-session");
        assertEquals(true, registered.get("registered"), registered.toString());
        assertEquals(true, registered.get("writes"));
        AtomicInteger writes = new AtomicInteger();
        JsonNode result = registry.execute(name, json.createObjectNode().put("text", " Alpha "), builtins, WRITES,
                (tool, arguments) -> {
                    writes.incrementAndGet();
                    assertEquals("edit_note", tool);
                    assertEquals("alpha", arguments.path("content").asText());
                    return staged.deepCopy();
                });
        assertEquals(1, writes.get());
        assertTrue(result.path("staged").asBoolean());
        assertEquals(42, result.path("action_id").asInt());
        assertFalse(result.has("output"));
    }

    private ObjectNode proposal(String name, String method) {
        ObjectNode proposal = json.createObjectNode().put("name", name)
                .put("description", "将合成输入文本清理首尾空白并统一英文字母大小写")
                .put("gap", "现有合成工具没有文本空白清理及大小写规范化组合能力")
                .put("capability_key", "synthetic_text_case_normalization");
        proposal.putArray("source_refs").addObject().put("path", SOURCE_PATH).put("sha256", SOURCE_HASH);
        ObjectNode schema = proposal.putObject("parameters").put("type", "object").put("additionalProperties", false);
        schema.putObject("properties").putObject("text").put("type", "string");
        schema.putArray("required").add("text");
        proposal.putArray("steps").addObject().put("id", "format").put("op", "code").put("language", "python")
                .put("source", "def run(input):\n    return input['text'].strip()." + method + "()\n").set("input", ref("input"));
        proposal.set("output", ref("steps.format"));
        ArrayNode tests = proposal.putArray("tests");
        tests.addObject().put("name", "normal synthetic text").put("kind", "normal")
                .put("expected", method.equals("upper") ? "ALPHA" : "alpha").set("input", json.createObjectNode().put("text", " Alpha "));
        tests.addObject().put("name", "empty synthetic input").put("kind", "boundary")
                .put("expected", "").set("input", json.createObjectNode().put("text", ""));
        return proposal;
    }

    private JsonNode execute(String name, String text) {
        JsonNode result = registry.execute(name, json.createObjectNode().put("text", text), builtins, WRITES,
                (tool, arguments) -> { throw new AssertionError("Unexpected primitive " + tool); });
        assertTrue(result.path("ok").asBoolean(), result.toString());
        return result;
    }

    private JsonNode state() throws Exception { return json.readTree(persisted.get()); }
    private ObjectNode ref(String path) { return json.createObjectNode().put("$ref", path); }
}
