package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dyh.learnhub.controller.CliCommandController.Argument;
import org.dyh.learnhub.controller.CliCommandController.Body;
import org.dyh.learnhub.controller.CliCommandController.Command;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAppPipelineTest {
    private static final String PAGE_AND_MOVE = """
            {"steps":[
              {"id":"found","command":"note.page","input":{"query":{"kw":"合成笔记"}}},
              {"id":"move","command":"folder.move-items","input":{"body":{
                "kind":"note","folderId":42,"ids":{"$ref":"found#/list","$pluck":"/id"}}}}
            ]}
            """;
    private static final String PAGINATE_AND_MOVE = """
            {"steps":[
              {"id":"found","command":"note.page","paginate":true,"input":{"query":{"kw":"合成笔记","size":2}}},
              {"id":"move","command":"folder.move-items","input":{"body":{
                "kind":"note","folderId":42,"ids":{"$ref":"found#/list","$pluck":"/id"}}}}
            ]}
            """;
    private static final String FOREACH_AND_MOVE = """
            {"steps":[
              {"id":"found","command":"note.page"},
              {"id":"details","command":"note.detail","forEach":{"$ref":"found#/list"},
                "input":{"path":{"id":{"$item":"/id"}}}},
              {"id":"move","command":"folder.move-items","input":{"body":{
                "kind":"note","folderId":42,"ids":{"$ref":"details#","$pluck":"/id"}}}}
            ]}
            """;
    private static final Body OBJECT = new Body(true, Map.of("type", "object"));
    private static final List<Argument> PAGE_ARGUMENTS = List.of(new Argument("kw", "query", false, "string", null),
            new Argument("page", "query", false, "integer", null), new Argument("size", "query", false, "integer", null));
    private static final List<Command> CATALOG = List.of(
            new Command("note.page", "GET", "/api/notes", PAGE_ARGUMENTS, null, "json"),
            new Command("file.page", "GET", "/api/files/page", PAGE_ARGUMENTS, null, "json"),
            new Command("note.detail", "GET", "/api/notes/{id}", List.of(
                    new Argument("id", "path", true, "integer", null)), null, "json"),
            new Command("folder.move-items", "POST", "/api/folders/move-items", List.of(), OBJECT, "json"),
            new Command("note.save", "POST", "/api/notes", List.of(), OBJECT, "json"),
            new Command("ai.chat", "POST", "/api/ai/chat", List.of(), OBJECT, "json"));

    private final ObjectMapper json = new ObjectMapper();
    private final Commands commands = new Commands(json);
    private final AgentAppPipeline pipeline = new AgentAppPipeline(json, commands);

    @Test
    void queryProjectsExactIdsAndFreezesOnlyTheBatchForApproval() throws Exception {
        commands.readResult = json.readTree("""
                {"list":[{"id":5,"title":"合成正文甲"},{"id":"9007199254740993"},{"id":"5"}],"total":4}
                """);

        var outcome = execute(PAGE_AND_MOVE);

        assertThat(commands.executed).containsExactly("note.page");
        assertThat(outcome.result()).isNull();
        assertThat(outcome.pending().command().id()).isEqualTo("folder.move-items");
        assertThat(outcome.pending().input().at("/body/ids").toString()).isEqualTo("[5,9007199254740993]");
        assertThat(outcome.pending().input().at("/body/folderId").asLong()).isEqualTo(42);
        assertThat(outcome.steps().at("/0/status").asText()).isEqualTo("ok");
        assertThat(outcome.steps().at("/0/resultCount").asInt()).isEqualTo(3);
        assertThat(outcome.steps().at("/0/total").asInt()).isEqualTo(4);
        assertThat(outcome.steps().at("/0/partial").asBoolean()).isTrue();
        assertThat(outcome.steps().at("/1/status").asText()).isEqualTo("pending");
        assertThat(outcome.steps().toString()).doesNotContain("合成正文甲", "9007199254740993");
    }

    @Test
    void readOnlyPipelineReturnsTheFinalEnvelopeAndSupportsEscapedPointerKeys() throws Exception {
        commands.readResult = json.readTree("""
                {"list":[{"nested/id~test":17}]}
                """);
        var outcome = execute("""
                {"steps":[
                  {"id":"search","command":"note.page"},
                  {"id":"detail","command":"note.detail","input":{"path":{"id":{"$ref":"search#/list/0/nested~1id~0test"}}}}
                ]}
                """);

        assertThat(commands.executed).containsExactly("note.page", "note.detail");
        assertThat(outcome.pending()).isNull();
        assertThat(outcome.result().path("ok").asBoolean()).isTrue();
        assertThat(outcome.result().path("command").asText()).isEqualTo("note.detail");
        assertThat(outcome.result().at("/result/path/id").asLong()).isEqualTo(17);
    }

    @Test
    void failedReadStopsBeforeStagingMove() throws Exception {
        commands.failure = true;
        var outcome = execute(PAGE_AND_MOVE);

        assertFailed(outcome, "app_rejected");
        assertThat(commands.executed).containsExactly("note.page");
        assertThat(outcome.steps()).hasSize(1);
        assertThat(outcome.steps().at("/0/status").asText()).isEqualTo("failed");
    }

    @Test
    void emptySelectionStopsBeforeStagingMove() throws Exception {
        commands.readResult = json.readTree("""
                {"list":[],"total":0}
                """);
        var outcome = execute(PAGE_AND_MOVE);

        assertFailed(outcome, "empty_selection");
        assertThat(commands.executed).containsExactly("note.page");
    }

    @Test
    void missingProjectedFieldStopsBeforeStagingMove() throws Exception {
        commands.readResult = json.readTree("""
                {"list":[{"id":1},{"title":"合成笔记无 ID"}],"total":2}
                """);
        var outcome = execute(PAGE_AND_MOVE);

        assertFailed(outcome, "missing_reference");
        assertThat(commands.executed).containsExactly("note.page");
    }

    @Test
    void rejectsForwardReferenceAndNonfinalWriteBeforeStartingAnyReads() throws Exception {
        var forward = execute("""
                {"steps":[
                  {"id":"early","command":"note.page","input":{"query":{"kw":{"$ref":"late#/title"}}}},
                  {"id":"late","command":"note.page"}
                ]}
                """);
        assertFailed(forward, "invalid_input");

        var earlyWrite = execute("""
                {"steps":[
                  {"id":"found","command":"note.page"},
                  {"id":"write","command":"note.save","input":{"body":{}}},
                  {"id":"verify","command":"note.page"}
                ]}
                """);
        assertFailed(earlyWrite, "invalid_input");
        assertThat(commands.executed).isEmpty();
    }

    @Test
    void rejectsUnknownAndUnavailableCommandsBeforeStartingAnyReads() throws Exception {
        String steps = """
                {"steps":[{"id":"found","command":"note.page"},{"id":"run","command":"%s"}]}
                """;
        assertFailed(execute(steps.formatted("not.a.command")), "unknown_command");
        assertFailed(execute(steps.formatted("ai.chat")), "invalid_input");
        assertThat(commands.executed).isEmpty();
    }

    @Test
    void passesReturnedReferenceShapedObjectsAsLiteralData() throws Exception {
        commands.readResult = json.readTree("""
                {"list":[{"title":{"$ref":"unplanned#/content"}}]}
                """);
        var outcome = execute("""
                {"steps":[
                  {"id":"found","command":"note.page"},
                  {"id":"save","command":"note.save","input":{"body":{"$ref":"found#/list/0"}}}
                ]}
                """);

        assertThat(outcome.pending().input().at("/body/title/$ref").asText()).isEqualTo("unplanned#/content");
        assertThat(commands.executed).containsExactly("note.page");
    }

    @Test
    void validatesExplicitDestinationAndPositiveIds() throws Exception {
        var absent = execute("""
                {"steps":[{"id":"move","command":"folder.move-items","input":{"body":{"kind":"file","ids":[3]}}}]}
                """);
        assertFailed(absent, "invalid_input");

        var invalidId = execute("""
                {"steps":[{"id":"move","command":"folder.move-items","input":{"body":{"kind":"file","folderId":0,"ids":[0]}}}]}
                """);
        assertFailed(invalidId, "invalid_input");

        var root = execute("""
                {"steps":[{"id":"move","command":"folder.move-items","input":{"body":{"kind":"file","folderId":null,"ids":[3]}}}]}
                """);
        assertThat(root.pending().input().at("/body/folderId").isNull()).isTrue();
        assertThat(commands.executed).isEmpty();
    }

    @Test
    void rejectsExpandedInputAndExpiredPipelineWithoutStagingWrite() throws Exception {
        commands.readResult = json.getNodeFactory().textNode("x".repeat(100_000));
        var oversized = execute("""
                {"steps":[
                  {"id":"found","command":"note.page"},
                  {"id":"save","command":"note.save","input":{"body":{"copies":[{"$ref":"found#"},{"$ref":"found#"},{"$ref":"found#"}]}}}
                ]}
                """);
        assertFailed(oversized, "input_limit");

        var expired = pipeline.execute(json.readTree(PAGE_AND_MOVE), System.currentTimeMillis() - 1);
        assertFailed(expired, "deadline");
        assertThat(commands.executed).containsExactly("note.page");
    }

    @Test
    void aggregatesAllPagesInOrderAndKeepsQueryFilters() throws Exception {
        commands.resultOverride = request -> request.input().at("/query/page").asInt() == 1
                ? page(3, 103, 102) : page(3, 101);

        var outcome = execute(PAGINATE_AND_MOVE);

        assertThat(commands.executed).containsExactly("note.page", "note.page");
        assertThat(commands.inputs).extracting(input -> input.at("/query/page").asInt()).containsExactly(1, 2);
        assertThat(commands.inputs).allSatisfy(input -> {
            assertThat(input.at("/query/kw").asText()).isEqualTo("合成笔记");
            assertThat(input.at("/query/size").asInt()).isEqualTo(2);
        });
        assertThat(outcome.steps().at("/0/pageCount").asInt()).isEqualTo(2);
        assertThat(outcome.steps().at("/0/total").asInt()).isEqualTo(3);
        assertThat(outcome.steps().at("/0/resultCount").asInt()).isEqualTo(3);
        assertThat(outcome.steps().at("/0/partial").asBoolean()).isFalse();
        assertThat(outcome.pending().input().at("/body/ids").toString()).isEqualTo("[103,102,101]");
    }

    @Test
    void usesFileDefaultSizeAndPreservesMetadataWhenStartingAfterPageOne() throws Exception {
        commands.resultOverride = request -> page(23, 21, 22, 23).put("page", 2).put("size", 20);
        var outcome = execute("""
                {"steps":[{"id":"found","command":"file.page","paginate":true,"input":{"query":{"page":2}}}]}
                """);

        assertThat(commands.executed).containsExactly("file.page");
        assertThat(commands.inputs.getFirst().at("/query/size").asInt()).isEqualTo(20);
        assertThat(outcome.result().at("/result/list")).hasSize(3);
        assertThat(outcome.result().at("/result/page").asInt()).isEqualTo(2);
        assertThat(outcome.result().at("/result/size").asInt()).isEqualTo(20);
        assertThat(outcome.steps().at("/0/startPage").asInt()).isEqualTo(2);
        assertThat(outcome.steps().at("/0/partial").asBoolean()).isTrue();
    }

    @Test
    void rejectsInconsistentPagesWithoutStagingWrite() throws Exception {
        for (JsonNode invalidPage : List.of(page(4, 3, 4), page(3, 2), page(3), page(3, 3, 4))) {
            commands.resultOverride = request -> request.input().at("/query/page").asInt() == 1
                    ? page(3, 1, 2) : invalidPage;
            var outcome = execute(PAGINATE_AND_MOVE);
            assertFailed(outcome, "pagination_inconsistent");
            assertThat(outcome.steps()).hasSize(1);
            assertThat(outcome.steps().at("/0/pageCount").asInt()).isEqualTo(1);
        }

        commands.resultOverride = request -> page(2, 1, 2).put("page", 2).put("size", 20);
        assertFailed(execute("""
                {"steps":[{"id":"found","command":"file.page","paginate":true}]}
                """), "pagination_inconsistent");
        assertThat(commands.executed).containsOnly("note.page", "file.page");
    }

    @Test
    void rejectsOversizedPaginationBeforeFetchingMorePages() throws Exception {
        commands.resultOverride = request -> page(501, 1, 2);
        assertFailed(execute(PAGINATE_AND_MOVE), "pagination_limit");

        commands.resultOverride = request -> page(129, 1, 2);
        assertFailed(execute(PAGINATE_AND_MOVE), "call_limit");
        assertThat(commands.executed).containsExactly("note.page", "note.page");
    }

    @Test
    void foreachPassesEachItemAndExposesOutputsForTheFinalBulkWrite() throws Exception {
        commands.resultOverride = request -> request.command().id().equals("note.page")
                ? page(3, 3, 1, 2)
                : json.createObjectNode().set("id", request.input().at("/path/id"));

        var outcome = execute(FOREACH_AND_MOVE.replace("\"$ref\":\"found#/list\"",
                "\"$ref\":\"found#/list\",\"$pluck\":\"/id\"").replace("\"$item\":\"/id\"", "\"$item\":\"\""));

        assertThat(commands.executed).containsExactly("note.page", "note.detail", "note.detail", "note.detail");
        assertThat(commands.inputs.subList(1, 4)).extracting(input -> input.at("/path/id").asInt())
                .containsExactly(3, 1, 2);
        assertThat(outcome.steps().at("/1/iterationCount").asInt()).isEqualTo(3);
        assertThat(outcome.steps().at("/1/resultCount").asInt()).isEqualTo(3);
        assertThat(outcome.pending().input().at("/body/ids").toString()).isEqualTo("[3,1,2]");
    }

    @Test
    void failedOrEmptyLoopNeverStagesTheBulkWrite() throws Exception {
        commands.readResult = page(3, 1, 2, 3);
        commands.reject = request -> request.input().at("/path/id").asInt() == 2;

        var failed = execute(FOREACH_AND_MOVE);
        assertFailed(failed, "app_rejected");
        assertThat(failed.steps()).hasSize(2);
        assertThat(failed.steps().at("/1/iterationCount").asInt()).isEqualTo(1);
        assertThat(commands.executed).containsExactly("note.page", "note.detail", "note.detail");

        commands.readResult = page(0);
        var empty = execute(FOREACH_AND_MOVE);
        assertFailed(empty, "empty_selection");
        assertThat(empty.steps().at("/1/status").asText()).isEqualTo("ok");
        assertThat(empty.steps().at("/1/iterationCount").asInt()).isZero();
        assertThat(commands.executed).containsExactly("note.page", "note.detail", "note.detail", "note.page");
    }

    @Test
    void foreachHonorsSharedCallAndOutputBudgets() throws Exception {
        long[] ids = java.util.stream.LongStream.rangeClosed(1, 64).toArray();
        commands.readResult = page(64, ids);
        assertFailed(execute(FOREACH_AND_MOVE), "call_limit");
        assertThat(commands.executed).containsExactly("note.page");

        JsonNode large = json.getNodeFactory().textNode("x".repeat(120_000));
        commands.resultOverride = request -> request.command().id().equals("note.page") ? page(5, 1, 2, 3, 4, 5) : large;
        var oversized = execute(FOREACH_AND_MOVE);
        assertFailed(oversized, "output_limit");
        assertThat(oversized.steps().at("/1/iterationCount").asInt()).isEqualTo(4);
    }

    @Test
    void preflightsInvalidModifiersWithoutExecutingAnyRead() throws Exception {
        String searchThen = """
                {"steps":[{"id":"found","command":"note.page"},%s]}
                """;
        for (String invalid : List.of(
                """
                {"id":"bad","command":"note.detail","paginate":true}
                """,
                """
                {"id":"bad","command":"note.save","forEach":{"$ref":"found#/list"}}
                """,
                """
                {"id":"bad","command":"note.page","paginate":true,"forEach":{"$ref":"found#/list"}}
                """,
                """
                {"id":"bad","command":"note.detail","input":{"path":{"id":{"$item":"/id"}}}}
                """,
                """
                {"id":"bad","command":"note.detail","forEach":{"$ref":"bad#/list"}}
                """)) {
            assertFailed(execute(searchThen.formatted(invalid)), "invalid_input");
        }
        assertThat(commands.executed).isEmpty();
    }

    private ObjectNode page(long total, long... ids) {
        ObjectNode page = json.createObjectNode().put("total", total);
        var rows = page.putArray("list");
        for (long id : ids) rows.addObject().put("id", id);
        return page;
    }

    private AgentAppPipeline.Outcome execute(String input) throws Exception {
        return pipeline.execute(json.readTree(input), System.currentTimeMillis() + 5_000);
    }

    private static void assertFailed(AgentAppPipeline.Outcome outcome, String code) {
        assertThat(outcome.pending()).isNull();
        assertThat(outcome.result().path("ok").asBoolean()).isFalse();
        assertThat(outcome.result().path("code").asText()).isEqualTo(code);
    }

    private static final class Commands extends AgentAppCommands {
        private final ObjectMapper json;
        private final List<String> executed = new ArrayList<>();
        private final List<JsonNode> inputs = new ArrayList<>();
        private JsonNode readResult;
        private boolean failure;
        private Predicate<Prepared> reject = ignored -> false;
        private Function<Prepared, JsonNode> resultOverride;

        private Commands(ObjectMapper json) {
            super(json, () -> CATALOG, () -> 0);
            this.json = json;
        }

        @Override
        public JsonNode run(Prepared prepared, long deadline) {
            assertThat(prepared.requiresApproval()).isFalse();
            String command = prepared.command().id();
            executed.add(command);
            inputs.add(prepared.input());
            boolean rejected = failure || reject.test(prepared);
            var out = json.createObjectNode().put("command", command).put("ok", !rejected);
            if (rejected) return out.put("code", "app_rejected");
            JsonNode result = resultOverride == null
                    ? (command.equals("note.page") ? readResult : prepared.input())
                    : resultOverride.apply(prepared);
            return out.set("result", result);
        }
    }
}
