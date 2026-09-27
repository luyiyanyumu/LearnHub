/**
 * 「把智能体的回答融入当前笔记」的共享契约。
 *
 * <h3>为什么单独抽一个文件</h3>
 * 这条链路横跨两个互不相识的组件：**悬浮面板**（`AgentPanel`，挂在 `AppLayout` 上，
 * 任何页面都在）与**笔记编辑页**（`NoteEdit`）。它们只靠 `window` 事件通信
 * （与仓库里 `lh-agent-open` / `lh-ask-agent` 是同一套做法，避免组件强耦合）。
 * 事件名如果两边各写一份字符串，改一侧忘一侧的后果是"点了没反应"，而且**不会报错**、
 * 控制台干干净净 —— 所以事件名统一放这里，两边 import 同一份。
 *
 * <h3>职责划分（为什么是"面板只递材料、页面干活"）</h3>
 * <ul>
 *   <li><b>面板</b>知道"是哪条回答"（它手里有问答）；</li>
 *   <li><b>编辑页</b>知道"这篇笔记现在长什么样"（含**尚未保存**的改动）、有进度弹窗与预览按钮，
 *       也知道往哪个笔记 id 落。</li>
 * </ul>
 * 所以面板只发一条「请把这条回答融进当前笔记」，剩下的（调模型、显示进度、预览、替换正文）
 * 都由编辑页完成，并通过 {@link AGENT_NOTE_MERGE_RESULT_EVENT} 回报成功或失败 —— 面板据此
 * 把按钮恢复成可重试状态，而不是永远打勾。
 *
 * <h3>为什么"融入"由模型整篇重写，而不是追加到文末</h3>
 * 追加会把"关于并发注意事项的回答"贴到讲基础语法的笔记末尾，知识与它的上下文分家，
 * 同一主题问两次就出现两段近似内容。改为让模型读完整篇 + 新内容，产出把新知识放到
 * 该在位置的新正文（提示词见 `skills/note-merge/SKILL.md`）。代价是长笔记有长度闸门，
 * 且必须由用户预览确认后替换 —— **AI 只交候选正文，提交决定留给人**。
 */

/** 笔记编辑页 → 面板：我现在开着哪篇笔记（`noteId` 为空表示"新建、还没保存"） */
export const AGENT_NOTE_CONTEXT_EVENT = 'lh-agent-note-context'

/** 面板 → 笔记编辑页：请把这条回答融入当前笔记 `{ noteId, question, answer }` */
export const AGENT_NOTE_MERGE_EVENT = 'lh-agent-note-merge'

/** 笔记编辑页 → 面板：融入的结果 `{ noteId, ok, message }`（失败时面板要能重试） */
export const AGENT_NOTE_MERGE_RESULT_EVENT = 'lh-agent-note-merge-result'
