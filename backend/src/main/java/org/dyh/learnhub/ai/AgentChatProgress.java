package org.dyh.learnhub.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天任务的进度板：记录编排里程碑与可安全外露的执行事实（工具名、参数摘要、结果摘要、耗时等），
 * 绝不承载模型的私有思考内容。
 *
 * <p>每一行进度都会保存在内存中（供 {@link #snapshot()} 读取），并尽量通过
 * {@link AgentService.ChatListener} 实时推送；每行都带步骤 id，便于回填状态与结果。</p>
 */
final class AgentChatProgress {
    /** 进度行数量上限，超出后按插入顺序淘汰最早的行，避免长任务无限占用内存。 */
    private static final int MAX_STEPS = 160;
    /** 所有进度行附加详情（工具参数、结果等）的字符总数上限，防止超大结果撑爆快照。 */
    private static final int MAX_DETAILS_CHARS = 900_000;
    /** 事件监听器，用于把进度实时推送给前端；为空表示只保存在内存中。 */
    private final AgentService.ChatListener listener;
    /** 进度板创建时刻（纳秒），用于计算本轮总耗时。 */
    private final long started = System.nanoTime();
    /** 步骤 id → 该步骤的公开字段（kind、title、status、detail 等），按插入顺序保留。 */
    private final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    /** 步骤 id → 开始时刻（纳秒），仅用于计算单步耗时，不对外输出。 */
    private final Map<String, Long> starts = new LinkedHashMap<>();
    /** 自增序号，用于生成稳定的步骤 id（step-1、step-2……）。 */
    private int sequence;
    /** 会话 id；为空表示本轮还没有可归属的会话。 */
    private String sessionId;
    /** 是否已经记录过用户消息，用于判断本轮进度是否允许持久化。 */
    private boolean userRecorded;
    /** 本轮是否由当前实例占有会话，避免同一会话被多个执行者同时推进。 */
    private boolean ownsSession;
    /** 已累计写入详情的字符数，与 {@link #MAX_DETAILS_CHARS} 比较后决定是否还接受新详情。 */
    private int detailsChars;

    /** 绑定底层监听器；进度事件最终通过它推送给前端。 */
    AgentChatProgress(AgentService.ChatListener listener) {
        this.listener = listener;
    }

    /** 记录本轮会话 id 并通知监听器；推送失败不影响主流程，因为传输层无法取消已暂存的工作。 */
    void session(String id) {
        sessionId = id;
        if (listener != null) {
            try { listener.onSession(id); } catch (RuntimeException ignored) { /* Transport cannot cancel staged work. */ }
        }
    }

    /** 当前会话 id，可能为 null（例如系统触发的自动任务）。 */
    String sessionId() { return sessionId; }
    /** 标记用户消息已落库，此后进度才有持久化价值。 */
    void userRecorded() { userRecorded = true; }
    /** 是否允许持久化本轮进度：必须同时具备会话 id 且用户消息已记录。 */
    boolean canPersist() { return sessionId != null && userRecorded; }
    /** 标记本轮由当前实例占有会话（用于并发去重）。 */
    void ownSession() { ownsSession = true; }
    /** 当前实例是否占有该会话。 */
    boolean ownsSession() { return ownsSession; }
    /** 从进度板创建至今的毫秒数，作为本轮总耗时写入进度事件。 */
    long elapsedMs() { return Math.max(0L, (System.nanoTime() - started) / 1_000_000); }

    /** 发起一个“阶段（phase）”进度步骤，例如“正在思考”“正在整理答案”。 */
    String phase(String title, String detail) { return start("phase", title, detail, null, null); }

    /** 两参重载：不带调用参数地发起一个工具步骤（无参数的工具）。 */
    String tool(String name, String parentId) {
        return start("tool", toolTitle(name), null, compact(name, 80), parentId);
    }

    /**
     * 发起一个“工具调用”进度步骤。
     *
     * @param name      工具名（用于展示与选择标题文案）
     * @param parentId  父步骤 id，可为空
     * @param arguments 调用参数的 JSON 文本，仅取摘要后展示
     * @return 步骤 id
     */
    String tool(String name, String parentId, String arguments) {
        return start("tool", toolTitle(name), null, compact(name, 80), parentId,
                AgentProgressDetails.tool(name, arguments));
    }

    /**
     * 通用的“开始一个步骤”入口：分配 id、记录开始时刻、登记公开字段并推送。
     *
     * @param kind     步骤类型（phase、tool 等）
     * @param title    展示标题
     * @param detail   展示详情（会做长度限制）
     * @param toolName 工具名，非工具步骤传 null
     * @param parentId 父步骤 id，可为空
     * @return 新步骤 id
     */
    String start(String kind, String title, String detail, String toolName, String parentId) {
        return start(kind, title, detail, toolName, parentId, Map.of());
    }

    /** 内部重载：同上，并额外接收初始详情（如工具参数、计划等）。 */
    private String start(String kind, String title, String detail, String toolName, String parentId,
                         Map<String, Object> details) {
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
        details(id, details);
        emit(row);
        return id;
    }

    /** 把步骤标记为已完成，并写入完成详情。 */
    void complete(String id, String detail) { finish(id, "completed", detail); }

    /** 记录本轮模型选择了哪些工具（仅摘要），作为该步骤的附加详情。 */
    void selectedTools(String id, JsonNode calls) { details(id, AgentProgressDetails.tools(calls)); }

    /** 记录该步骤引用的资料来源列表（标题、URL 等公开信息）。 */
    void sources(String id, List<Map<String, Object>> sources) { details(id, AgentProgressDetails.sources(sources)); }

    /** 记录工具返回结果的摘要（不保存模型私有思考）。 */
    void result(String id, String tool, JsonNode result) { details(id, AgentProgressDetails.result(tool, result)); }

    /** 给指定步骤追加详情，并受总字符数预算约束；步骤不存在时直接忽略。 */
    private void details(String id, Map<String, Object> details) {
        Map<String, Object> row = rows.get(id);
        if (row == null || details.isEmpty()) return;
        int before = detailSize(row);
        Map<String, Object> merged = new LinkedHashMap<>(row);
        merged.putAll(details);
        int after = detailSize(merged);
        if (detailsChars - before + after > MAX_DETAILS_CHARS) {
            // Keep steps/statuses and their already shown facts when this exceptionally long trace fills up.
            if (!row.containsKey("result")) row.put("result", "本轮执行明细已达到保存上限，后续仍保留步骤与状态。");
            return;
        }
        row.putAll(details);
        detailsChars += after - before;
    }

    /** 估算一行进度中详情字段的字符数，用于总量预算控制。 */
    private static int detailSize(Map<String, Object> row) {
        int size = 0;
        for (String key : List.of("tools", "sources", "websites", "result", "error")) {
            Object value = row.get(key);
            if (value != null) size += value.toString().length();
        }
        return size;
    }

    /** 给步骤绑定“本轮可执行操作”的编号，供前端按钮点击后回传。 */
    void action(String id, long actionId) {
        if (actionId > 0 && rows.containsKey(id)) rows.get(id).put("actionId", actionId);
    }

    /** 结束一个步骤：写入最终状态与耗时，推送后清理开始时刻。 */
    void finish(String id, String status, String detail) {
        Map<String, Object> row = rows.get(id);
        if (row == null) return;
        row.put("status", status);
        if (detail != null && !detail.isBlank()) row.put("detail", compact(detail, 240));
        row.put("elapsedMs", Math.max(0L, (System.nanoTime() - starts.get(id)) / 1_000_000));
        emit(row);
    }

    /** 一步到位的“更新”事件：先发起一个 update 步骤，再立即标记完成。 */
    void update(String title, String detail) {
        complete(start("update", title, detail, null, null), detail);
    }

    /** 上报任务计划（待办清单），用于前端展示与刷新计划面板。 */
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

    /** 本轮结束时把仍处于“进行中”的步骤统一标记为失败，避免界面一直转圈。 */
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

    /** 返回当前所有进度行的快照副本，调用方可以安全地序列化与遍历。 */
    List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> copy = new ArrayList<>();
        rows.values().forEach(row -> copy.add(Map.copyOf(row)));
        return List.copyOf(copy);
    }

    /** 通过监听器推送一行进度；推送异常被吞掉，防止进度问题中断主任务。 */
    private void emit(Map<String, Object> row) {
        if (listener == null) return;
        try { listener.onProgress(Map.copyOf(row)); } catch (RuntimeException ignored) { /* History still records the completed task. */ }
    }

    /** 截断过长文本（保留前缀并追加省略号），用于展示层。 */
    private static String compact(String value, int max) {
        if (value == null) return "";
        String text = value.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 把工具名翻译成面向用户的中文动作标题。 */
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
            case "write_note_sections" -> "分批生成笔记片段";
            case "set_reply_actions" -> "设置本轮操作选项";
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
