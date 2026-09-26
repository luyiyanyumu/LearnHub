-- 补齐论文用例（2026-09-23）
-- 起因：自测发现评测集严重偏斜 —— note 65 条，而 file 占索引 77%（580 块）却只有 4 条，
-- 其中 file#6（AgentRewind，88k 字）一条都没有。指标因此只证明"笔记检索得好"。
-- 这批用例全部**照论文实际内容**出（读过摘要/方法/实验段），不是凭空编。
INSERT INTO rag_eval (question, expect_refs, expect_words, note, enabled) VALUES
-- file#6 AgentRewind: Recoverable Execution for Long-Horizon LLM Agents
('长时程智能体跑任务时，早期出现的错误会带来什么后果？', 'file:6', '错误传播;上下文;环境状态', '论文要解决的问题（摘要）', 1),
('智能体在执行过程中可能对环境造成哪些破坏？', 'file:6', '删文件;配置;数据库', '摘要里点名的三类环境破坏', 1),
('这篇论文用什么基准来评估长时程工程任务？', 'file:6', 'MettleBench', '论文自建基准（专有名词，测词面）', 1),
('AgentRewind 和 Continue 的恢复能力是怎么对比出来的？', 'file:6', '50;失败轨迹;配对', '实验设计（50 条重复失败轨迹配对）', 1),
('消融实验主要验证了哪两个设计的作用？', 'file:6', '状态恢复;rewind memory', '消融结论（aligned state restoration / rewind memory）', 1),
('要从失败轨迹里恢复，需要把哪些东西一起还原？', 'file:6', '上下文;环境状态;对齐', '方法核心：上下文与环境状态对齐恢复', 1),

-- file#5 In-the-Flow Agentic System Optimization (AgentFlow)
('主流的工具增强方法在训练上有什么问题？', 'file:5', '单体策略;全上下文;长时程', '论文动机（monolithic policy + full context）', 1),
('AgentFlow 和单体策略训练的区别是什么？', 'file:5', 'in-the-flow;模块化', '方法定位（在流程中优化 vs 单策略）', 1),
('系统里负责判断这次执行是不是跑偏了的模块叫什么？', 'file:5', 'Execution Verifier', '模块名（附录案例里出现 Execution Verifier: STOP）', 1),
('这篇论文的代码和演示在哪？', 'file:5', 'agentflow.stanford.edu', '论文给出的网址（测词面）', 1),

-- 跨来源：论文 + 自己的笔记（测融合是否真能把两条证据拼起来）
('这些论文里提到的验证器/自检思路，跟我笔记里 Agent 的 Planning 部分有什么关系？', 'file:5|note:44', 'Planning;验证', '跨来源：论文模块 vs 笔记里的 Agent 循环', 1),
('长时程智能体的失败恢复，和我笔记里记的 Agent 循环有什么关系？', 'file:6|note:44', 'Agent 循环;恢复', '跨来源：论文的 recovery vs 笔记的循环结构', 1);
