package org.dyh.learnhub.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dyh.learnhub.entity.AgentEvent;
import org.dyh.learnhub.entity.AgentPendingAction;
import org.dyh.learnhub.entity.AgentSession;
import org.dyh.learnhub.mapper.AgentEventMapper;
import org.dyh.learnhub.mapper.AgentPendingActionMapper;
import org.dyh.learnhub.mapper.AgentSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能体会话：事件日志的读写与「投影」。
 *
 * <h3>为什么要有这一层</h3>
 * 原来后端是**完全无状态**的：对话历史由前端把最近 12 条整体带回来，超出的直接丢弃。
 * 后果有三个：① 刷新页面就丢上下文；② 老消息无声消失，模型会突然"忘事"；
 * ③ 后端没有任何审计线索。改为「事件落库 + 按需投影」后，这三件事一起解决。
 *
 * <h3>事件 ≠ 上下文</h3>
 * 落库的事件是**全量**的（含每次工具调用与结果），但发给模型的上下文是**投影**：
 * <ul>
 *   <li>只取 user / assistant 的最终文本；</li>
 *   <li><b>不回放工具调用与工具结果</b> —— 一次 search_knowledge 就可能带回几万字的笔记片段，
 *       回放会让每轮上下文被撑爆，收益远小于成本；工具事件留在库里供审计与界面回看。</li>
 *   <li>只取最近 {@link #PROJECTION_ROUNDS} 轮；更早的内容由 summary 事件承担（见 {@link #latestSummary}）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private final AgentSessionMapper sessionMapper;
    private final AgentEventMapper eventMapper;
    private final AgentPendingActionMapper actionMapper;

    /** 投影给模型的最近轮数（一轮 = 一问一答），再早的靠摘要兜 */
    public static final int PROJECTION_ROUNDS = 8;

    /** 会话标题取首条用户消息的前 N 个字符 */
    private static final int TITLE_LEN = 30;

    /** 角色常量：只在这里定义一次，避免各处字符串写错 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";
    public static final String ROLE_SUMMARY = "summary";

    /**
     * 取会话；不存在则创建。
     * <p>
     * 传了 sessionId 但库里查不到（清过库、换了浏览器、导出了旧数据）时，
     * **沿用这个 id 新建**而不是另发一个 —— 前端不必感知，它的 localStorage 里那个 id 继续有效。
     *
     * @param sessionId 前端带来的会话 id，可为空（空 = 开新会话）
     * @param noteId    发起时所在的笔记 id，可为空
     * @param firstText 首条用户消息，用于生成标题
     * @return 实际使用的会话 id
     */
    public String ensure(String sessionId, Long noteId, String firstText) {
        if (StringUtils.hasText(sessionId)) {
            AgentSession exists = sessionMapper.selectById(sessionId);
            if (exists != null) {
                return exists.getId();
            }
            return create(sessionId, noteId, firstText);
        }
        return create(UUID.randomUUID().toString(), noteId, firstText);
    }

    private String create(String id, Long noteId, String firstText) {
        AgentSession s = new AgentSession();
        s.setId(id);
        s.setNoteId(noteId);
        s.setTitle(title(firstText));
        sessionMapper.insert(s);
        log.info("新建智能体会话 {}（标题：{}）", id, s.getTitle());
        return id;
    }

    // ------------------------------------------------------------------
    // 会话管理：列表 / 新建 / 改标题 / 换模型 / 删除
    // ------------------------------------------------------------------

    /**
     * 会话列表（按最后活动时间倒序）。
     * <p>右边栏要用它做"新开不同会话"的入口。带出消息条数与会话自己的模型档案，
     * 这样列表上就能显示"这条会话用的是哪个模型"。
     */
    public List<Map<String, Object>> list(int limit) {
        List<AgentSession> rows = sessionMapper.selectList(Wrappers.<AgentSession>lambdaQuery()
                .orderByDesc(AgentSession::getUpdatedAt)
                .last("LIMIT " + Math.max(1, Math.min(200, limit))));
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentSession s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("title", s.getTitle());
            m.put("noteId", s.getNoteId());
            m.put("modelProfileId", s.getModelProfileId());
            m.put("updatedAt", s.getUpdatedAt() == null ? null : String.valueOf(s.getUpdatedAt()).replace('T', ' '));
            m.put("createdAt", s.getCreatedAt() == null ? null : String.valueOf(s.getCreatedAt()).replace('T', ' '));
            Long n = eventMapper.selectCount(Wrappers.<AgentEvent>lambdaQuery()
                    .eq(AgentEvent::getSessionId, s.getId()));
            m.put("events", n == null ? 0 : n.intValue());
            out.add(m);
        }
        return out;
    }

    /** 显式新建一个空会话（界面上点"新对话"） */
    public Map<String, Object> createNew(String title, String modelProfileId) {
        String id = UUID.randomUUID().toString();
        AgentSession s = new AgentSession();
        s.setId(id);
        s.setTitle(StringUtils.hasText(title) ? title(title) : "新对话");
        s.setModelProfileId(StringUtils.hasText(modelProfileId) ? modelProfileId.trim() : null);
        sessionMapper.insert(s);
        log.info("新建智能体会话 {}（模型档案：{}）", id, s.getModelProfileId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", s.getTitle());
        m.put("modelProfileId", s.getModelProfileId());
        m.put("events", 0);
        return m;
    }

    /** 改标题 */
    public void rename(String sessionId, String title) {
        AgentSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("会话不存在：" + sessionId);
        }
        s.setTitle(title(title));
        sessionMapper.updateById(s);
    }

    /** 指定本会话用哪个模型档案（空 = 跟随分工表） */
    public void setModelProfile(String sessionId, String profileId) {
        AgentSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new IllegalArgumentException("会话不存在：" + sessionId);
        }
        s.setModelProfileId(StringUtils.hasText(profileId) ? profileId.trim() : null);
        sessionMapper.updateById(s);
    }

    /** 本会话指定的模型档案（没有则 null = 跟随分工表） */
    public String modelProfileOf(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        AgentSession s = sessionMapper.selectById(sessionId);
        return s == null ? null : s.getModelProfileId();
    }

    /** 标题：压平换行、截断到 TITLE_LEN，空消息给个兜底名 */
    private static String title(String text) {
        if (!StringUtils.hasText(text)) {
            return "新对话";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= TITLE_LEN ? t : t.substring(0, TITLE_LEN) + "…";
    }

    /** 追加一条事件（append-only），并顺带把会话的 updated_at 推到当前时间 */
    public void append(String sessionId, String role, String content, String toolName, String toolCallId) {
        AgentEvent e = new AgentEvent();
        e.setSessionId(sessionId);
        e.setRole(role);
        e.setContent(content);
        e.setToolName(toolName);
        e.setToolCallId(toolCallId);
        eventMapper.insert(e);
        sessionMapper.touch(sessionId);
    }

    /** 该会话的全部事件，按时间正序 */
    public List<AgentEvent> allEvents(String sessionId) {
        return eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getSessionId, sessionId)
                .orderByAsc(AgentEvent::getId));
    }

    /**
     * 最近的 summary 事件内容（没有则返回 null）。
     */
    public String latestSummary(String sessionId) {
        List<AgentEvent> rows = eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getSessionId, sessionId)
                .eq(AgentEvent::getRole, ROLE_SUMMARY)
                .orderByDesc(AgentEvent::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getContent();
    }

    /** 最新摘要事件的 id —— 它就是「已经被概括到哪」的水位线（watermark） */
    private Long latestSummaryEventId(String sessionId) {
        List<AgentEvent> rows = eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getSessionId, sessionId)
                .eq(AgentEvent::getRole, ROLE_SUMMARY)
                .orderByDesc(AgentEvent::getId)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getId();
    }

    /** 一次压缩最少要覆盖多少条事件 —— 太小会导致「每轮都触发一次摘要」 */
    public static final int COMPACT_MIN_EVENTS = 8;

    /**
     * 算出这一轮需要被压缩的事件：**已滑出投影窗口、且尚未被摘要覆盖**的那部分。
     *
     * <p>空列表 = 本轮不用压缩。这里刻意留了迟滞（{@link #COMPACT_MIN_EVENTS}）：
     * 否则一旦总轮数超过窗口，就会每轮都触发一次摘要调用，平白多一次模型往返。
     * 代价是"未被覆盖的溢出部分"在攒够之前仍看不到 —— 这个取舍写在文档里，别当成 bug。
     *
     * @param rounds 投影窗口轮数（与 {@link #projection} 保持一致）
     */
    public List<AgentEvent> pendingCompaction(String sessionId, int rounds) {
        List<AgentEvent> turns = turns(sessionId);
        int keep = Math.max(2, rounds * 2);
        if (turns.size() <= keep) {
            return List.of();
        }
        List<AgentEvent> older = turns.subList(0, turns.size() - keep);
        Long watermark = latestSummaryEventId(sessionId);
        List<AgentEvent> uncovered = new ArrayList<>();
        for (AgentEvent e : older) {
            if (watermark == null || e.getId() > watermark) {
                uncovered.add(e);
            }
        }
        return uncovered.size() >= COMPACT_MIN_EVENTS ? uncovered : List.of();
    }

    /** 会话里全部 user/assistant 事件（时间正序），投影与压缩共用 */
    private List<AgentEvent> turns(String sessionId) {
        return eventMapper.selectList(Wrappers.<AgentEvent>lambdaQuery()
                .eq(AgentEvent::getSessionId, sessionId)
                .in(AgentEvent::getRole, List.of(ROLE_USER, ROLE_ASSISTANT))
                .orderByAsc(AgentEvent::getId));
    }

    /**
     * 投影出「模型可见的历史」：最近 {@code rounds} 轮的一问一答，不含工具事件。
     * <p>
     * 刻意不做的事：不回放 role=tool 的事件（理由见类注释）；
     * 不做"保留开头、丢弃中间"那种花样截断 —— 要保留更早的信息就靠摘要，不靠猜。
     *
     * @return 按时间正序的 user/assistant 事件
     */
    public List<AgentEvent> projection(String sessionId, int rounds) {
        List<AgentEvent> all = turns(sessionId);
        int max = Math.max(2, rounds * 2); // 一轮 = 一问一答
        if (all.size() <= max) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - max, all.size()));
    }

    /**
     * 界面回看：把事件还原成「气泡列表」，并把落在问答之间的工具事件挂到该轮回答上。
     * <p>
     * 这是"投影"的另一个用途 —— 同一条事件流，模型看到的是精简问答，界面看到的是带工具角标的过程。
     */
    public List<Map<String, Object>> messagesForDisplay(String sessionId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<String> pendingTools = new ArrayList<>();
        for (AgentEvent e : allEvents(sessionId)) {
            switch (e.getRole()) {
                case ROLE_USER -> {
                    pendingTools.clear();
                    out.add(bubble(ROLE_USER, e.getContent(), List.of()));
                }
                case ROLE_TOOL -> {
                    if (StringUtils.hasText(e.getContent())) {
                        pendingTools.add(e.getContent());
                    }
                }
                case ROLE_ASSISTANT -> {
                    out.add(bubble(ROLE_ASSISTANT, e.getContent(), new ArrayList<>(pendingTools)));
                    pendingTools.clear();
                }
                default -> {
                    // summary 是给模型用的内部事件，不在界面出现
                }
            }
        }
        return out;
    }

    private static Map<String, Object> bubble(String role, String content, List<String> events) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        m.put("events", events);
        m.put("toolUsed", !events.isEmpty());
        return m;
    }

    /** 会话元信息（不存在返回 null） */
    public AgentSession get(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionMapper.selectById(sessionId) : null;
    }

    /** 删除会话及其事件（界面上"清空对话"用） */
    public void delete(String sessionId) {
        eventMapper.deleteBySessionId(sessionId);
        actionMapper.deleteBySessionId(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    // ------------------------------------------------------------------
    // 待确认的写操作（审批）
    // ------------------------------------------------------------------

    /**
     * 把一个写操作挂成"待确认"。
     * <p>{@code argsJson} 原样保存：确认时按这份参数执行，不重新推导 ——
     * 否则用户在确认前又聊了几轮，实际执行的参数可能已经不是他看到的那一份。
     */
    public AgentPendingAction stageAction(String sessionId, String toolName, String argsJson, String summary) {
        AgentPendingAction a = new AgentPendingAction();
        a.setSessionId(sessionId);
        a.setToolName(toolName);
        a.setArgsJson(argsJson);
        a.setSummary(summary);
        a.setStatus("pending");
        actionMapper.insert(a);
        log.info("写操作进入待确认：{}（{}）", summary, toolName);
        return a;
    }

    /** 某会话里尚未处理的操作，按先后顺序 */
    public List<AgentPendingAction> pendingActions(String sessionId) {
        return actionMapper.selectList(Wrappers.<AgentPendingAction>lambdaQuery()
                .eq(AgentPendingAction::getSessionId, sessionId)
                .eq(AgentPendingAction::getStatus, "pending")
                .orderByAsc(AgentPendingAction::getId));
    }

    public AgentPendingAction getAction(Long id) {
        return actionMapper.selectById(id);
    }

    /** 标记为已确认 / 已取消 */
    public void resolveAction(Long id, String status) {
        AgentPendingAction a = new AgentPendingAction();
        a.setId(id);
        a.setStatus(status);
        a.setResolvedAt(LocalDateTime.now());
        actionMapper.updateById(a);
    }

    /** 给界面用的简短形状 */
    public static Map<String, Object> actionBrief(AgentPendingAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("toolName", a.getToolName());
        m.put("summary", a.getSummary());
        return m;
    }
}
