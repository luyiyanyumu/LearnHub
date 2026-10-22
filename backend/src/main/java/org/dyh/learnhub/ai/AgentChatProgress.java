package org.dyh.learnhub.ai;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real orchestration milestones only; never model reasoning, prompts or tool arguments. */
final class AgentChatProgress {
    private static final int MAX_STEPS = 160;
    private final AgentService.ChatListener listener;
    private final long started = System.nanoTime();
    private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    private final Map<String, Long> starts = new LinkedHashMap<>();
    private int sequence;
    private String sessionId;
    private boolean userRecorded;
    private boolean ownsSession;

    AgentChatProgress(AgentService.ChatListener listener) {
        this.listener = listener;
    }

    void session(String id) {
        sessionId = id;
        if (listener != null) {
            try { listener.onSession(id); } catch (RuntimeException ignored) { /* Transport cannot cancel staged work. */ }
        }
    }

    String sessionId() { return sessionId; }
    void userRecorded() { userRecorded = true; }
    boolean canPersist() { return sessionId != null && userRecorded; }
    void ownSession() { ownsSession = true; }
    boolean ownsSession() { return ownsSession; }
    long elapsedMs() { return Math.max(0L, (System.nanoTime() - started) / 1_000_000); }

    String phase(String title, String detail) { return start("phase", title, detail, null, null); }

    String tool(String name, String parentId) {
        return start("tool", toolTitle(name), null, compact(name, 80), parentId);
    }

    String start(String kind, String title, String detail, String toolName, String parentId) {
        if (rows.size() >= MAX_STEPS) return null;
        String id = "step-" + (++sequence);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", kind);
        row.put("status", "running");
        row.put("title", compact(title, 100));
        if (detail != null && !detail.isBlank()) row.put("detail", compact(detail, 240));
        if (toolName != null) row.put("toolName", toolName);
        if (parentId != null) row.put("parentId", parentId);
        row.put("elapsedMs", 0L);
        row.put("at", Instant.now().toString());
        rows.put(id, row);
        starts.put(id, System.nanoTime());
        emit(row);
        return id;
    }

    void complete(String id, String detail) { finish(id, "completed", detail); }

    void action(String id, long actionId) {
        if (actionId > 0 && rows.containsKey(id)) rows.get(id).put("actionId", actionId);
    }

    void finish(String id, String status, String detail) {
        Map<String, Object> row = rows.get(id);
        if (row == null) return;
        row.put("status", status);
        if (detail != null && !detail.isBlank()) row.put("detail", compact(detail, 240));
        row.put("elapsedMs", Math.max(0L, (System.nanoTime() - starts.get(id)) / 1_000_000));
        emit(row);
    }

    void update(String title, String detail) {
        complete(start("update", title, detail, null, null), detail);
    }

    void plan(List<Map<String, Object>> plan) {
        // Stable rows are updated in place; replaced plans remove obsolete trailing rows.
        List<String> obsolete = rows.keySet().stream().filter(id -> id.startsWith("plan-"))
                .filter(id -> Integer.parseInt(id.substring(5)) > plan.size()).toList();
        for (String id : obsolete) finish(id, "cancelled", "计划已调整，此步骤不再需要");
        for (int i = 0; i < plan.size(); i++) {
            Map<String, Object> item = plan.get(i);
            String id = "plan-" + (i + 1);
            String status = String.valueOf(item.get("status"));
            if ("in_progress".equals(status)) status = "running";
            Map<String, Object> row = rows.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
            if (!status.equals(row.get("status"))) starts.put(id, System.nanoTime());
            row.put("id", id); row.put("kind", "plan"); row.put("status", status);
            row.put("title", compact(String.valueOf(item.get("step")), 160));
            row.put("detail", "任务计划 · 进度由工具结果核对");
            row.put("elapsedMs", 0L); row.put("at", Instant.now().toString());
            emit(row);
        }
    }

    void failRunning(String detail) {
        boolean marked = false;
        for (var entry : rows.entrySet()) {
            if ("running".equals(entry.getValue().get("status"))) {
                finish(entry.getKey(), "error", detail);
                marked = true;
            }
        }
        if (!marked) finish(phase("本轮未完成", null), "error", detail);
    }

    List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> copy = new ArrayList<>();
        rows.values().forEach(row -> copy.add(Map.copyOf(row)));
        return List.copyOf(copy);
    }

    private void emit(Map<String, Object> row) {
        if (listener == null) return;
        try { listener.onProgress(Map.copyOf(row)); } catch (RuntimeException ignored) { /* History still records the completed task. */ }
    }

    private static String compact(String value, int max) {
        if (value == null) return "";
        String text = value.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    static String toolTitle(String tool) {
        if (tool == null) return "执行工具";
        return switch (tool) {
            case "query_notes" -> "查找笔记";
            case "search_knowledge" -> "检索知识库";
            case "get_note" -> "读取笔记原文";
            case "get_quick_ref" -> "读取速查卡";
            case "get_file" -> "读取资料原文";
            case "list_files" -> "查找资料";
            case "list_categories" -> "查看分类";
            case "search_wiki" -> "查找 Wiki 导览";
            case "read_wiki" -> "读取 Wiki 章节";
            case "graph_neighbors", "graph_query" -> "探索知识图谱关系";
            case "search_code" -> "检索代码库";
            case "get_code" -> "读取代码片段";
            case "web_search" -> "搜索网络资料";
            case "web_fetch", "fetch_url" -> "读取网页";
            case "create_note" -> "准备新笔记";
            case "update_note", "append_to_note", "edit_note" -> "准备笔记修改";
            case "write_long_note" -> "生成完整笔记";
            case "create_quick_ref", "update_quick_ref" -> "准备速查卡修改";
            case "add_file_from_url" -> "准备导入资料";
            case "create_word_document" -> "准备 Word 文档";
            case "inspect_capabilities" -> "检查现有工具与项目源码";
            case "evolve_tool" -> "审查并测试自适应工具";
            case "test_adaptive_tool" -> "试运行代码工具与合成测试";
            case "manage_adaptive_tool" -> "管理自适应工具版本";
            case "update_plan" -> "更新任务计划";
            default -> tool.startsWith("adaptive_") ? "执行自适应工作流" : "执行工具";
        };
    }
}
