# AI Agent 讲义（从零到能讲清楚）

## 0. 资料来源与阅读方式

这份讲义的主体是从**你资料库里的那份《AI Agent 面试全攻略》**（`ai-agent-interview-guide-zh.pdf`，2026 年 4 月版 v1.0.2，约 19.9 万字）整理出来的，我把它的 9 大模块重排成"先讲机制、再讲方法、最后讲工程"的顺序。

联网只用来核对两个**协议层的最新状态**，分别在 §6.3（MCP）和 §8.5（A2A）标注了 URL。协议类信息变化快，凡是引用网页的地方我都给了链接，你可以自己复核。

两点说明：

- 文档第 07 模块（大模型基础）我这轮只读到目录，没有逐段读取正文，所以 §9 是按标准知识写的**速览**，不是对那份 PDF 的转述。
- 你笔记里关于 Harness 架构、上下文压缩（Compaction、大输出落盘）和 Ralph Loop 判定逻辑的记录**并不完整**，所以本讲义里讲压缩时用的是 PDF 里"摘要压缩 / MemGPT 分页"这一条线，和你的 Harness 笔记不是同一套叙述。

---

## 1. Agent 是什么

### 1.1 严谨定义

AI Agent（智能体）指：**以大语言模型为推理核心，结合规划、记忆与工具调用，在多步交互中根据环境反馈持续决策并完成任务的系统**。

关键在最后四个字——**闭环**。普通 LLM 调用是"输入 Prompt → 输出文本"，输出之后就结束了，这叫**开环生成**。Agent 的输出往往是"下一步动作"，动作执行后环境返回结果，结果再进入下一轮推理，这叫**闭环决策**。

### 1.2 那句公式怎么拆

业界通用写法：

```text
Agent ≈ LLM + Planning（规划）+ Memory（记忆）+ Tools（工具）
```

| 组件 | 职责 | 缺了会怎样 |
|---|---|---|
| LLM | 理解、推理、生成自然语言与结构化计划（中枢） | 不成立，它是认知核心 |
| Planning | 把模糊目标拆成可执行步骤，并在执行中动态调整 | 变成"只会接话"的聊天，长任务容易跑偏或一步登天完不成 |
| Memory | 短期上下文 + 长期知识 | 长对话丢线索，跨会话无法延续用户偏好与任务状态 |
| Tools | 搜索、数据库、代码执行、API 调用等 | 只能"空谈"，无法查实时信息、改系统状态 |

注意最后一列的逻辑：**LLM 仍是中枢，但单靠 LLM 加外环才构成完整 Agent**。缺组件不是"少个功能"，是能力形态发生变化。

### 1.3 和 ChatBot、LLM Chain 的区别

这是面试高频对比题，PDF 里给了一张维度表：

| 维度 | ChatBot | LLM Chain | Agent |
|---|---|---|---|
| 控制流 | 多为线性对话 | 开发者定义的 DAG / 序列 | 模型驱动的分支与循环 |
| 工具 | 可有可无 | 可嵌入固定节点 | 动态选择与多轮调用 |
| 状态 | 主要是会话上下文 | 链各节点显式传递 | 记忆 + 环境观察 |
| 适用 | 问答、闲聊、简单引导 | ETL 式固定流程 | 开放问题、研究、自动化任务 |

**本质区别一句话**：

- Chain：控制流写在**代码**里。
- Agent：控制流在**模型决策 + 环境反馈**里（但仍可由代码设边界）。

由此可以回答一个常见的模糊问题："ChatBot 加插件算不算 Agent？"——**不一定**。如果插件调用由固定规则（如关键词路由）触发，那是"带工具的 Bot"；如果由模型在多步推理中自主选择工具与参数、形成闭环迭代，才更贴近 Agent。判据是**是否具备多步自主决策与反馈闭环**。

同理，"RAG + Chat"算不算 Agent：单次检索再回答，偏"增强型 Chat"；如果有**多轮检索策略**（查不到换查询词、分解子问题、交叉验证），则具备 Agent 特征。

### 1.4 Agent 的工作流程

PDF 用五个字概括：**听懂 → 拆活 → 动手 → 对账 → 交卷**。展开成五步：

1. **输入处理**：意图识别、指代消解、安全过滤、加载相关记忆与文档。
2. **任务分解**：生成子任务列表或决策树；复杂任务可配 Human-in-the-loop（人在回路）确认里程碑。
3. **工具选择与调用**：由模型或路由模块选工具；执行器负责鉴权、限流、结果规范化。
4. **结果整合**：合并多源信息，解决冲突（例如时间更新的数据优先）。
5. **输出生成**：面向用户的自然语言答案 + 可选的引用与操作轨迹（便于审计）。

**"迷路"怎么防**（高频题）：明确停止条件与最大步数；维护任务清单 todo 与当前子目标；每步输出要求结构化（JSON）；关键步骤强制验证。

---

## 2. Agent 的核心组成

比"四件套"更细一层是六个部件。注意这里出现了两个容易和"规划"混的部件：**执行**和**反思**。

| 部件 | 英文 | 做什么 | 技术要点 |
|---|---|---|---|
| 感知 | Perception | 把多模态输入（文本、文件、接口返回、页面结构）转成模型可用的表示，抽取任务相关状态 | 常与结构化抽取、OCR、HTML 解析、日志解析结合；关键是**减少噪声进上下文** |
| 规划 | Planning | 目标拆成子目标与步骤；可一次性计划，也可每步重规划 | CoT / ReAct、Planner-Executor 双模块、树搜索（LATS）、任务图 |
| 记忆 | Memory | 短期：当前会话上下文、工具轨迹；长期：用户画像、文档知识、向量库、图数据库 | 摘要压缩、引用溯源、记忆冲突解决、权限与隐私 |
| 工具 | Tools | 对外部世界可执行操作的抽象 | 需有清晰 schema（名称、描述、参数 JSON Schema）；**最小权限、参数校验、错误信息回灌模型** |
| 执行 | Action | 真正调用工具或触发环境变化 | 处理超时、重试、幂等等工程问题 |
| 反思 | Reflection | 对失败或质量不佳的结果自省：纠错、换策略、生成检查清单 | 可做成独立子调用："列出本次推理的三处风险并修正" |

关于**规划与执行要不要拆成两个模型**：视任务而定。Planner-Executor 拆分可提升可控性（强模型规划、快模型执行）；单模型端到端更简单但易在长链路漂移。可以混合：规划用强模型，执行层做确定性校验。

关于**反思是不是必须**：不是必须，但对高 stakes（高风险高代价）或易错工具场景收益大；成本是额外延迟与 Token。

---

## 3. 最小执行循环（可运行示例）

抽象范式是 **Thought（推理）→ Action（行动）→ Observation（观察）→ … → Final Answer**。实现上由"编排层（Orchestrator）"驱动：解析模型输出、执行工具、把结果写回上下文，直到满足停止条件。

PDF 里的最小 Python 骨架（我补了注释）：

```python
def run_agent(user_goal: str, tools: dict, llm, max_steps: int = 8):
    messages = [
        {"role": "system", "content": "你是可调用工具的 Agent。"},
        {"role": "user", "content": user_goal},
    ]
    for _ in range(max_steps):
        plan = llm.chat(messages)        # 模型决定：结束 or 调用某工具
        action = parse_tool_call(plan)   # 从模型输出解析结构化动作
        if action.name == "finish":
            return action.args["answer"]
        obs = tools[action.name](**action.args)   # 真实执行
        messages.append({"role": "assistant", "content": plan})
        messages.append({"role": "user", "content": f"工具结果：{obs}"})
    return "超过最大步数，未完成。"
```

三个工程要点：

1. **Observation 只能来自工具，不能由模型自己编造**（服务端要校验）。
2. **停止条件**通常组合使用：模型声明 finish、任务清单全部完成、达到步数/预算上限、超时、连续无进展检测、外部成功信号（如测试通过）。生产环境**必须有硬上限**防死循环。
3. 工具 Schema 用标准结构描述，示例如下：

```python
tools = [
  {
    "type": "function",
    "function": {
      "name": "search_kb",
      "description": "在公司知识库中按关键词搜索",
      "parameters": {
        "type": "object",
        "properties": {
          "query": {"type": "string"},
          "top_k": {"type": "integer", "default": 5}
        },
        "required": ["query"]
      }
    }
  }
]
```

关于**工具描述（tool description）为什么极重要**：模型靠描述做工具选择；描述不清会导致选错工具、参数幻觉。好的描述包含：**何时用、何时不用、参数含义、错误示例、返回格式**。在 LangChain 里写 Tool 的 docstring 时特别要写"何时不要用"，这是减少误触发的线上质量关键。

---

## 4. 核心框架篇

### 4.1 ReAct（面试必考）

**全称 Reasoning + Acting（推理 + 行动）**。一句话：把"思考过程"和"工具使用"写进同一条轨迹里，让推理可监督、可纠错、可复现。

它解决的问题是：模型单轮问答容易"空想"出事实；ReAct 通过**显式推理 + 工具反馈**把推理锚定在真实环境上。**关键不是"更会想"，而是"想与做的闭环"**。

轨迹格式：

```text
用户问题
  → Thought：拆解子目标
  → Action：选择工具并执行
  → Observation：获得外部反馈
  → Thought：根据观察修正计划
  → …
  → Final Answer
```

标准流程伪代码：

```text
输入: question, tools, llm, max_steps
初始化: trajectory = []
for step in 1..max_steps:
    prompt = build_react_prompt(question, tools, trajectory)
    text = llm.generate(prompt)
    if "Final Answer:" in text:
        return extract_final_answer(text)
    thought = parse_thought(text)      # 从 Thought: ... 解析
    action = parse_action(text)        # 从 Action: tool[arg] 解析
    obs = tools.execute(action)        # 真实环境反馈
    trajectory.append((thought, action, obs))
return "达到最大步数仍未结束" 或 强制总结
```

**解析要点（面试常问）**：

- Thought / Action / Observation 通常用**固定前缀**或**结构化格式（如 JSON）**便于程序解析。
- Observation 只能来自工具。
- 停止条件：出现 Final Answer，或步数上限，或检测到重复无效循环。

经典英文 Prompt 模板：

```text
You are a helpful assistant that can use tools to answer questions.
You must follow this format strictly:

Question: the input question you must answer
Thought: think step by step about what you should do next
Action: the action to take, must be one of [{tool_names}]
Action Input: the input to the action
Observation: the result of the action
... (this Thought/Action/Action Input/Observation can repeat)
Thought: I now know the final answer
Final Answer: the final answer to the original question

Begin!
Question: {question}
Thought: {optional_seed_thought}
```

PDF 还给了一个更适合工程化的中文 JSON 版模板：每轮先输出 Thought（一两句话说明为什么采取下一步），再输出 Action 为严格 JSON `{"tool":"工具名","input":{...}}`；不需要工具时输出 `{"tool":"finish","input":{"answer":"..."}}`；并强调"**你会收到 Observation，不要编造 Observation**"。

### 4.2 Plan-and-Execute

和 ReAct 的差别记成：**ReAct 是"走一步看一步"；Plan-and-Execute 先有总蓝图，再落地**。适合步骤多、依赖关系清晰的任务。

两阶段模式：

```text
输入任务
  → Planner：输出计划 P = [step1, step2, ...]
  → Executor：for each step:
        执行（可调用工具/子 Agent）
        更新状态 state
        若失败或信息不足 → 触发 Re-planning
  → 输出最终结果
```

**Planner 设计**：输入是用户目标、约束（时间/预算/格式）、当前已知上下文；输出是结构化计划（步骤列表、每步子目标、依赖、所需工具类型）。技巧是**计划粒度适中**——过细易 brittle（脆弱），过粗难执行。

**Executor 设计**：输入是当前步骤和 state（已完成结果、中间变量）；输出是步骤产物 + 新 state；每步可选不同工具。

**重规划（Re-planning）**：触发条件为工具失败/返回空/超时、Observation 与假设冲突、新信息使原步骤多余或顺序错误。做法分**局部修复**（只替换失败步骤之后的子计划）和**全局重规划**（回到目标重新生成，成本高但更稳）。要防"计划抖动"：限制重规划次数、局部优先、在 state 中保留已验证事实、对计划变更加一致性检查。

对比表（PDF 原表）：

| 维度 | ReAct | Plan-and-Execute |
|---|---|---|
| 规划 | 隐式、逐步 | 显式、先全局后局部 |
| 灵活性 | 高（随时改工具） | 中（依赖重规划机制） |
| 成本 | 步数多时可很高 | 规划一次可能省执行盲目性 |
| 风险 | 短视 | 计划错误会波及全局 |

### 4.3 Reflexion

核心：**做完不等于结束——还要评估做得好不好，把教训记下来，下次带着教训重试**。像考试做错后写错题本，而不是盲目刷同一道题。

它强调**语言化反思**（自然语言反思条目），不是只改参数；反思作为**记忆**影响后续尝试策略。

三类角色分工：**Actor**（生成行动/答案，可接工具）、**Evaluator**（给反馈：对/错、评分、缺失项）、**Reflector**（写反思文本，指出错误原因与改进策略）。

注意反思要**可执行**，例如"应先确认单位换算""应先检索最新数据而非凭记忆"，而不是泛泛的"我要更仔细"。

流程：

```text
初始化 reflections = []
repeat until success or max_trials:
    Action: 基于任务 + reflections 生成输出（可含工具）
    Evaluation: 规则/模型评估（二元成功或细粒度批评）
    Reflection: 生成改进建议文本
    将 reflection 追加到 memory
```

和"让模型自己检查一遍"的区别：自检往往是一次性的；Reflexion 把评估与反思**显式化、结构化**，并**跨尝试复用**反思文本，形成可累积的"策略记忆"。

适用：可验证任务（代码、数学、有测试用例的生成）、易犯系统性错误的任务、预算允许多轮尝试的场景。**追问预警**：Evaluator 从哪来？答案是规则、更强模型、单元测试、外部工具验证，因任务而异。

### 4.4 LATS（Language Agent Tree Search）

把 Agent 决策看成在**树**上搜索：节点是一种"状态/中间思路"，分支是不同行动。任务复杂、存在多条可能路径时，与其一次走到底，不如探索多条路，用评估函数判断哪条更有希望。

它常与**蒙特卡洛树搜索（MCTS）**结合：

```text
while budget remains:
    Select: 从根沿策略选到叶（UCB 等平衡探索/利用）
    Expand: 生成若干可能的下一步（语言分支）
    Simulate/Rollout: 用启发式或模型快速评估结果潜力
    Backpropagate: 把评估回报回传到路径上的节点统计量
```

成本来自多分支扩展与多次评估/模拟；收益是降低"一条路走到黑"的局部最优风险。工程难点是分支爆炸、评估器设计、延迟控制，需要强剪枝与缓存。

### 4.5 LangChain 与 LangGraph

LangChain 把 LLM、提示模板、工具、记忆、解析器拼成可运行程序。核心抽象：

- **Tools**：对外部能力的封装（name、description、args schema、执行函数）。
- **LLM / ChatModel**：生成下一步决策。
- **Agent（推理策略）**：如何把工具、提示、中间步骤组合起来。
- **AgentExecutor**：驱动循环——调用 Agent → 若有 tool_calls 则执行工具 → 回填消息 → 直到停止。

AgentExecutor 解决的**核心问题**是：把"模型决策 → 工具执行 → 结果回填 → 再决策"的控制流标准化，统一处理迭代限制（`max_iterations`）、解析错误（`handle_parsing_errors`）、中间消息结构。

自写 LangChain Agent 的通用 checklist：定义 Tools（清晰 description 与 schema）→ 选提示模板 → 选模型与输出解析器 → 组装 Agent + AgentExecutor → 评测与加固（日志、重试、工具超时）。面试中强调你**理解 Executor 循环与 tool calling 模式**即可，不必背每个 API 名。

### 4.6 其它

PDF 还列了 AutoGen / CrewAI 等多 Agent 框架（见 §8.5），以及"多 Agent 协作 vs 单 Agent 多工具"的选型（见 §8.1）。

---

## 5. RAG 篇

RAG（Retrieval-Augmented Generation，检索增强生成）严格讲是给生成模型外挂一个可检索的知识源。它在 Agent 体系里的位置是"长期记忆 + 知识 grounding（接地/有据可依）"的一种实现。

### 5.1 完整流程与索引

文档 ETL 链路：**PDF/Word 解析 → 分块 → 向量化 → 入库**。

向量索引选型的经验规则：

| 场景 | 选择 |
|---|---|
| 数据量小、要简单 | HNSW + 精确参数调优 |
| 数据量大、内存紧 | IVF + PQ 组合（如 IVF_PQ） |
| 要磁盘级大规模 | DiskANN 类方案 |
| 强过滤 | 选对元数据索引友好的实现（Qdrant、Milvus 过滤能力强） |

最小可运行示例：

```python
import faiss
import numpy as np
from sentence_transformers import SentenceTransformer

model = SentenceTransformer("BAAI/bge-small-zh-v1.5")
texts = ["条款A", "条款B", "无关内容C"]
emb = model.encode(texts, normalize_embeddings=True).astype("float32")

dim = emb.shape[1]
index = faiss.IndexFlatIP(dim)   # 归一化后内积 = 余弦相似度
index.add(emb)

q = model.encode(["和A相关的查询"], normalize_embeddings=True).astype("float32")
D, I = index.search(q, k=2)
print("scores:", D, "indices:", I)
```

### 5.2 检索策略：向量、BM25、混合、RRF

- **向量检索**：Query 与文档块 embedding 后做 Top-K 最近邻。短板是专有名词、型号、编号等**精确匹配**不如关键词检索。
- **关键词检索（BM25）**：经典的词频-逆文档频率加权排序函数，擅长精确词匹配。
- **混合检索（Hybrid Search）**：BM25 分数 + 向量分数线性加权或归一化后融合，兼顾语义与字面。

```python
def min_max_norm(scores):
    s_min, s_max = min(scores), max(scores)
    if s_max == s_min:
        return [1.0 for _ in scores]
    return [(s - s_min) / (s_max - s_min) for s in scores]

def hybrid_fuse(vec_scores, bm25_scores, alpha=0.5):
    v = min_max_norm(vec_scores)
    b = min_max_norm(bm25_scores)
    return [alpha * vi + (1 - alpha) * bi for vi, bi in zip(v, b)]
```

- **RRF（Reciprocal Rank Fusion，倒数排名融合）**：不依赖原始分数尺度，把多路检索的**排名**融合：

```text
RRF(d) = Σ_r  1 / (k + rank_r(d))      常取 k = 60
```

```python
from typing import Dict, List, Sequence

def rrf_fuse(ranked_lists: Sequence[Sequence[str]], k: int = 60) -> List[tuple[str, float]]:
    """ranked_lists: 多路检索结果，每路为 doc_id 从优到劣的列表。"""
    scores: Dict[str, float] = {}
    for ranks in ranked_lists:
        for rank, doc_id in enumerate(ranks, start=1):
            scores[doc_id] = scores.get(doc_id, 0.0) + 1.0 / (k + rank)
    return sorted(scores.items(), key=lambda x: x[1], reverse=True)
```

**为什么混合检索比单路向量更有效**：向量捕获语义，但可能漏掉专有名词与编号；BM25 补精确匹配。二者融合提高鲁棒性，尤其在技术文档与电商场景。

**RRF 与加权融合怎么选**：当两路分数量纲一致或已可靠归一化时，加权融合直观可调；当一路是排名、一路是概率、或分数不可比时，RRF 更稳，且少调参（经典 k=60）。

### 5.3 查询侧优化

| 方法 | 做什么 | 风险 / 注意 |
|---|---|---|
| 查询改写（Query Rewriting） | 把用户原始问句改写成更易被检索匹配的形式，或生成多条查询变体做多路召回 | LLM 可能篡改实体或添加未提及条件。对策：多路检索 + RRF、重排、引用校验；高敏场景用人工词表 + 模板。**建议原句 + 改写句同时检索再融合** |
| HyDE（假设文档嵌入） | 让 LLM 先写"假想的答案文档"（可能含错），再对假想文档做 embedding 去检索 | 拉近查询与文档在向量空间的距离，缓解表述风格不一致；但假想文档可能引入错误主题，需重排与引用校验。**不适合强事实约束且模型易胡编的领域** |
| 子问题分解 | 对多跳、多条件问题，先拆成子问题序列，每个子问题检索，再拼接或图式合并证据 | 失败模式：分解错误（实体指代错）、子问题遗漏约束、合并时矛盾未检测 |
| Step-back Prompting | 先让模型生成更抽象的后退一步的问题（背景原理），再并行检索"具体问题 + 抽象问题"合并上下文 | 补充背景知识，减少只抓到细枝末节；比 HyDE 克制（仍停留在问题空间） |

一句话区分 HyDE 与 Step-back：**二者都试图拉近查询与文档的语义距离**，HyDE 用假想答案文档做 embedding（更激进，易引入虚构事实），Step-back 用更抽象的问题检索原理/背景类段落（相对克制）。实践中可并行检索后由重排裁决。

### 5.4 重排序（Reranking）

**为什么需要**：向量检索用的是 **Bi-Encoder**（双塔），为速度对 query 与 doc **独立编码**，交互信息不足；**Cross-Encoder** 把 query 与 doc **拼在一起**打分，精度更高但慢，所以放在 Top-K 之后做小范围重排。

| 类型 | 机制 | 优点 | 缺点 |
|---|---|---|---|
| Bi-Encoder | 两路编码，点积/余弦 | 快，可 ANN | 精度低于 CE |
| Cross-Encoder | 拼接后深度交互 | 精度高 | 慢，只能小批量 |

常用重排模型：Cohere Rerank API、BGE Reranker（如 `bge-reranker-large`，开源可本地部署）。

**MMR（最大边际相关性）**：在相关性与多样性间权衡，避免 Top-K 几乎重复的段落。公式：

```text
MMR = argmax_d [ λ·sim(q,d) − (1−λ)·max_{s∈S} sim(d,s) ]
```

λ 大偏相关，λ 小偏多样。

```python
import numpy as np

def mmr_select(query_vec: np.ndarray, doc_vecs: np.ndarray, top_k: int, lambda_mult: float = 0.5):
    """doc_vecs: shape (n, dim)，已归一化。"""
    sim_to_q = doc_vecs @ query_vec
    selected: list[int] = []
    candidates = set(range(len(doc_vecs)))
    while len(selected) < top_k and candidates:
        best_idx, best_score = None, -1e9
        for i in candidates:
            redundant = 0.0
            if selected:
                redundant = max(float(doc_vecs[i] @ doc_vecs[j]) for j in selected)
            score = lambda_mult * sim_to_q[i] - (1 - lambda_mult) * redundant
            if score > best_score:
                best_score, best_idx = score, i
        selected.append(best_idx)
        candidates.remove(best_idx)
    return selected
```

```python
from sentence_transformers import CrossEncoder

cross = CrossEncoder("BAAI/bge-reranker-base")
query = "员工年假天数"
docs = ["本公司年假为15天...", "报销应提交发票原件..."]
scores = cross.predict([[query, d] for d in docs])
ranked = sorted(zip(scores, docs), key=lambda x: x[0], reverse=True)
```

**重排放在哪一步**：召回 Top-K（几十到几百）→ Cross-Encoder 精排取 Top-N（3–10）→ 再生成，平衡延迟与效果。

### 5.5 RAG 高级模式（五个名词一次讲清）

| 模式 | 核心思想 | 与相邻概念的区别 |
|---|---|---|
| **GraphRAG** | 从文本抽取实体与关系构建图，检索时沿子图或社区摘要获取证据，适合多跳关系与全局问题（"整体主题是什么"） | 普通向量 RAG 擅长局部相似块；GraphRAG 强化关系推理与全局聚合。成本是构图与抽取成本高、抽取错误会污染图 |
| **Agentic RAG** | 由 Agent 决定何时检索、检索什么、是否再检索，可调用多工具 | 把 RAG 从"一次检索"变为多步决策循环；强调工具调用与规划 |
| **Self-RAG** | 模型在生成过程中插入"反思 token"：是否需要检索、检索内容是否有用、生成是否被支持 | 用反思 token/标签把决策**内嵌在生成格式中**，更偏训练与解码策略 |
| **Corrective RAG** | 当检索质量不达标时，触发额外检索（如网页搜索）或改写查询 | 典型触发信号：检索置信度低（Top1 与 Top2 差距小）、结果与问题实体不一致、重排后仍低分、生成与引用冲突 |
| **Adaptive RAG** | 按问题类型**路由**到不同链路：有的只需单跳向量检索，有的需多跳或工具 | Adaptive 强调路由策略，Agentic 强调循环决策与工具调用 |

**Agentic RAG 与 Self-RAG 的共同点**：都引入多步决策与反思；区别在 Agentic 常外显为工具调用与规划，Self-RAG 用反思 token 把"要不要检索、证据是否支持"内嵌在生成格式里。

**小公司是否值得上 GraphRAG**：若数据以说明文、FAQ 为主，向量 RAG + 混合检索 + 重排通常足够；图适合关系问题占比高且团队有图谱与评测能力时再投入。

### 5.6 RAG 评估

三个核心指标：

- **忠实度（Faithfulness）**：答案是否可由检索上下文推出，不编造。
- **上下文相关性（Context Relevance）**：检索块与问题是否相关。
- **答案正确性 / 有用性**：是否真正解决问题（人工或强模型裁判）。

**RAGAS** 提供一组基于 LLM 的指标（faithfulness、answer relevancy、context precision/recall）来自动化评估管道。注意裁判模型本身有偏差，应抽样人工复核。评估数据集构建：从真实日志脱敏抽样问题 → 标注标准答案或支持文档 ID → 覆盖简单事实、多跳、拒答、无答案等类型。

**为什么"只看最终答案对错"不够**：可能猜对，或上下文不相关仍生成；需同时评检索质量与忠实度，才能定位瓶颈在检索还是生成。

### 5.7 RAG 生产优化

- **索引优化**：调 ANN 参数（HNSW 的 M、efConstruction、efSearch）；分段分区（按租户、时间、产品线）减少搜索空间；权衡定期重建与增量插入。
- **缓存策略**：Query 级缓存（相同问题直接返回答案，注意权限与 TTL）、Embedding 缓存（热门 query 的向量）、LLM 响应缓存（低敏场景缩短延迟）。

---

## 6. 工具调用篇

### 6.1 Function Calling 到底是什么

**Function Calling 是厂商提供的结构化工具调用通道**（字段名、类型、与对话轮次绑定），模型产出的是结构化的调用意图，应用在本地执行并把结果回传，所以它是 Agent 的"手"。

**和"让模型输出 JSON"的本质区别**：纯 JSON 输出依赖 prompt 约束，解析脆弱、易混入闲聊文本；FC 更利于多轮 tool 消息与并行调用 ID 对齐。

**`tool_calls` 与 `tool` 消息的对应关系**：每条 `assistant.tool_calls[]` 有唯一 `id`；执行后每条结果作为一条 `role=tool` 消息，且**必须带相同 `tool_call_id`**，保证多并行调用时不错配。

**若模型不支持 FC 怎么办**：用 JSON mode / 约束解码 / 后处理抽取；或用小模型做"动作分类"。实践中可结合：FC 负责调度，JSON 负责业务负载。

**设计 JSON Schema 降低填错概率**：减少可选参数模糊性；用 `enum`；在 description 给示例；避免深层嵌套；必要时拆成多个函数。

### 6.2 MCP（Model Context Protocol）

PDF 里的定义（面试口径）：

- **MCP 解决的主要痛点**：工具集成碎片化、重复建设、难以跨产品复用；MCP 提供标准边界（Server）与传输，使工具像外设一样即插即用（在生态支持前提下）。
- **MCP 与 Function Calling 是替代关系吗？不是**。FC 是**模型侧**表达；MCP 是**工具侧**集成。Host 常将 MCP 工具列表映射为 FC 的 `tools`。

联网核对到的**最新状态**（协议信息变化快，请以官方为准）：

- 搜索结果指向 MCP 于 **2026-07-28 发布新版本规范**，被描述为"问世以来最大更新"，主打**无状态（stateless）核心**，并涉及扩展机制与直接发现（direct discovery）。官方变更页：<https://modelcontextprotocol.org/specification/2026-07-28/changelog>，官方博客：<https://blog.modelcontextprotocol.io/posts/2026-07-28/>。
- 说明：这页正文我这次抓取失败了（解析报错），所以**上面这条只依据搜索结果的标题与摘要**，具体条款细节请点开上面两个链接确认。

### 6.3 工具路由

**什么时候必须上工具路由**：当工具数量导致上下文膨胀、误选率上升或延迟/成本明显增加时。具体阈值依赖模型与描述长度，常见从**几十个工具**起考虑。

**为什么"让模型自己选工具"可能不如"路由器 + 规则"**：在域窄、路径稳定的场景，路由器更省成本、可测试、行为确定；全模型路由在开放域更灵活。最佳实践常是**混合**：易分类走规则，难例走模型。

**向量路由的缺陷与改进**：缺陷是描述不佳则 embedding 不准、OOV（未登录词）专有名词弱；改进是混合检索、同义词表、用户域特征、日志驱动迭代描述、加轻量分类器。

### 6.4 工具编排

- **静态链 vs 动态链**：静态链适合稳定 SOP；动态链适合开放域任务，但要防循环与成本失控。
- **条件工具调用**：根据中间结果分支，例如仅当 `risk_score > 0.8` 才调用 `human_review`。可用规则引擎、小模型分类，或让主模型输出结构化"分支字段"（需校验）。
- **依赖 DAG**：显式维护有向无环图——节点是工具调用，边是数据依赖；调度器拓扑排序执行；检测环；失败时重试或补偿。对长事务用 Saga 或幂等重试；对 AI 步骤用"检查点"持久化状态。
- **并行与串行的取舍**：读多且无依赖并行；有写冲突、强一致、或后一步参数必须来自上一步精确字段时串行；可并行读再串行写。
- **并行工具调用要注意**：幂等性、后端并发限制、数据竞争（写操作）、结果合并顺序、部分失败重试策略。

```python
import concurrent.futures, json
from typing import Any, Callable

def safe_call(name: str, fn: Callable, kwargs: dict[str, Any]) -> dict[str, Any]:
    try:
        return {"tool": name, "ok": True, "result": fn(**kwargs)}
    except Exception as e:
        return {"tool": name, "ok": False, "error": str(e)}

def run_parallel_tools(calls: list[tuple[str, Callable, dict[str, Any]]]):
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs = [ex.submit(safe_call, n, f, k) for n, f, k in calls]
        return [f.result() for f in futs]
```

### 6.5 工具安全（这一节面试很吃分）

| 保护面 | 做法 |
|---|---|
| 权限控制 | 模型本身没有用户身份，必须在**服务端**把"当前会话用户"与角色/权限绑定，执行工具前检查是否可读该表、是否可操作该租户。禁止把服务账号密钥交给模型侧推理环境，用用户 OAuth token 或后端代持且按最小权限 |
| 输入验证与清洗 | 防 Prompt 注入诱导越权参数；防 SQL 注入、路径穿越（`../../etc/passwd`）。对所有进入工具的字符串做白名单、参数化查询、chroot/沙箱 |
| 敏感操作确认 | 删除、转账、对外发邮件等需 HITL 或二次令牌；或把工具设计为"创建草稿"而非"直接发送"（两阶段提交式工具设计） |
| 调用频率限制 | 按用户/IP/工具维度 rate limit，防刷与成本失控；指数退避应对 429 |
| 审计日志 | 记录时间、trace/request id、用户/租户、工具名、参数摘要（脱敏）、结果状态、耗时、模型版本 |

**为什么 Calculator 禁止 `eval`**：`eval` 可执行任意 Python，等同于远程代码执行；应使用 AST 白名单或安全数学库：

```python
import ast, operator

_ALLOWED = {
    ast.Add: operator.add, ast.Sub: operator.sub,
    ast.Mult: operator.mul, ast.Div: operator.truediv,
    ast.USub: operator.neg, ast.Pow: operator.pow,
}

def eval_expr(node: ast.AST) -> float:
    if isinstance(node, ast.Constant) and isinstance(node.value, (int, float)):
        return float(node.value)
    if isinstance(node, ast.BinOp) and type(node.op) in _ALLOWED:
        return _ALLOWED[type(node.op)](eval_expr(node.left), eval_expr(node.right))
    if isinstance(node, ast.UnaryOp) and type(node.op) in _ALLOWED:
        return _ALLOWED[type(node.op)](eval_expr(node.operand))
    raise ValueError("unsupported expression")
```

文件操作工具同理，要限制根目录：

```python
import os
SANDBOX_ROOT = "/var/agent_sandbox"

def safe_read_file(path: str, max_bytes: int = 50_000) -> str:
    full = os.path.realpath(os.path.join(SANDBOX_ROOT, path))
    if not full.startswith(os.path.realpath(SANDBOX_ROOT) + os.sep):
        raise PermissionError("path escapes sandbox")
    with open(full, "rb") as f:
        return f.read(max_bytes).decode("utf-8", errors="replace")
```

数据库工具：永远参数化查询，只允许只读账号 + 白名单表 + 行级权限。代码执行工具：必须在沙箱（Docker、gVisor、WASM）中执行，限制 CPU/内存/网络，禁用危险模块，默认关闭或仅对内网。搜索结果不要整页 HTML 进上下文，用摘要与链接。

---

## 7. 记忆系统

### 7.1 为什么需要

**没有记忆，Agent 只能做"无状态函数调用"**：每次请求都像第一次见面，无法延续偏好、历史决策与上下文因果。

**状态 vs 上下文**：单次请求的 prompt 是瞬时输入；记忆是跨请求持久或半持久的状态，可被策略性注入 prompt、工具参数或规划模块。

**与 RAG 的关系**：长期记忆常与向量检索结合，但记忆还强调时间线、重要性、用户维度、写入策略——不只是"找文档"。所以问"用 RAG 算不算记忆"时，答：算长期记忆的一种实现路径，但完整记忆系统还包括写入策略、衰减、用户隔离、摘要与时间线。

### 7.2 分类体系

1. **按时间尺度**：短期（会话内）vs 长期（跨会话）。
2. **按内容类型**：程序性（怎么做）、陈述性（事实）、情景性（何时何地何人）、语义性（抽象知识）。
3. **按存储介质**：进程内存、Redis、关系库、向量库、图数据库、对象存储。
4. **按可控性**：显式记忆（用户确认保存）vs 隐式记忆（系统自动提炼）。

人类记忆类比（用于划分模块职责）：

| 心理学概念 | 大致特征 | Agent 中的常见对应 |
|---|---|---|
| 感觉记忆 | 极短、容量大、未加工 | 原始多模态输入缓存、流式 ASR 缓冲、截图/音频临时块 |
| 短期记忆 / 工作记忆 | 容量小、可主动操作 | 对话上下文、Working Memory、ReAct 轨迹中的"当前 scratchpad" |
| 长期记忆 | 持久、需巩固 | 向量库、文档库、用户画像表、知识图谱、会话摘要归档 |

**要注意的坑**：类比不能硬套——计算机没有神经可塑性，"巩固、遗忘、冲突解决"需要工程上显式实现。

### 7.3 短期记忆与摘要压缩

- **会话上下文（Conversation Buffer）**：把多轮 user/assistant（及可选 system）消息按时间顺序拼接进模型输入。信息保真度高；对话越长越贵、越慢、越易注意力分散。
- **滑动窗口记忆（Window Buffer）**：只保留最近 K 轮。
- **摘要压缩**：把早期对话压成摘要，解决成本与注意力问题，但**要防误差累积**。

### 7.4 情景记忆与语义记忆

**为什么区分存储**：因为更新频率、隐私级别、检索特征不同——情景更个人化、更时间敏感；语义更共享、更稳定。区分后可做不同保留策略（情景更易过期）、不同权限（情景多用户隔离更严格），并减少把"一次性事件"误当"长期规则"。

```python
from dataclasses import dataclass

@dataclass
class EpisodicRecord:          # 情景：具体发生过什么（个人化、带时间）
    id: str
    user_id: str
    ts: float
    summary: str               # 例如「2026-04-01 用户要求关闭自动续费」
    embedding_id: str          # 指向向量库中的向量

@dataclass
class SemanticFact:            # 语义：可共享或较稳定的事实/规则
    key: str                   # 例如 "billing.autorenew.policy"
    value: str
    source: str                # 文档版本、政策编号
    confidence: float
```

### 7.5 记忆检索策略（Generative Agents 三因子）

单一策略往往不够，需要混合：

- **基于时间**：最近对话优先，强时效任务（排障、联调）更有效；按 `created_at` 排序取最近 K 条，或时间衰减加权。
- **基于相关性**：语义相似优先，适合开放域问答、用户换说法。
- **基于重要性**：关键信息优先（项目目标、硬约束、用户级别偏好），适合"早期出现但仍必须遵守"的内容。
- **混合 pipeline**：三路召回（时间 / 向量 / 关键词）→ 去重 → 重排 → Token 截断注入。

斯坦福 Generative Agents 的记忆检索打分（PDF 引用）：

```text
score(m|q) = w_rel · rel̂(m,q) + w_rec · recencŷ(m) + w_imp · importancê(m)
```

- `rel̂`：查询与记忆的向量相似度映射到 [0,1]；
- `recencŷ`：随距上次发生/访问时间增大而下降（常用指数衰减）；
- `importancê`：由模型或规则给出的重要性归一化；
- 权重需调参，也可用乘法形式强调"必须同时满足"，线上要 AB 测试。

```python
from dataclasses import dataclass
import math, time
from typing import List

@dataclass
class Mem:
    id: str
    text: str
    importance: float       # 0~1
    last_access_ts: float   # 上次被访问/发生时间

def norm_minmax(values: List[float]) -> List[float]:
    if not values:
        return []
    lo, hi = min(values), max(values)
    if hi - lo < 1e-9:
        return [1.0 for _ in values]
    return [(v - lo) / (hi - lo) for v in values]

def generative_agent_style_scores(rel_scores, memories, w_rel=0.5, w_rec=0.3,
                                  w_imp=0.2, decay_per_hour=0.2) -> List[float]:
    now = time.time()
    recency_raw = [math.exp(-decay_per_hour * max(0.0, (now - m.last_access_ts) / 3600.0))
                   for m in memories]
    rel_n = norm_minmax(rel_scores)
    rec_n = norm_minmax(recency_raw)
    imp_n = [m.importance for m in memories]
    return [w_rel * rel_n[i] + w_rec * rec_n[i] + w_imp * imp_n[i]
            for i in range(len(memories))]
```

**只做强相关性检索会有什么问题**：会漏掉仍然有效但表述不相似的硬约束；也会过度偏向"像"但错误的片段。需要时间与重要性补足。

**去重与限长**：去重用语义去重（相似度阈值）或 canonical key（实体对齐）；限长按 rerank_score 排序后做 Token 装箱，或分层注入"摘要优先、细节按需"。注意**去重可能误删"相似但不同约束"**，要用阈值 + 冲突检测。

### 7.6 高级记忆框架

- **MemGPT / MemOS**：把 LLM 上下文当作"有限 RAM"，把外部存储当作"磁盘"，通过**分页/换入换出**与事件驱动控制信息进出上下文。当上下文将满时，由控制逻辑决定把哪些内容外溢到外部归档，需要时再加载回来。**与被动 RAG 的关键区别是"主动内存管理"**。要能讲清楚：外溢策略（FIFO、重要性、摘要）、主上下文 vs 外部上下文的边界、以及为什么能缓解长对话的 lost-in-the-middle 与成本问题。
- **Mem0**：面向应用的**记忆层（memory layer）**，把"从对话中抽取可复用记忆 → 更新 → 检索"做成可集成组件，常与向量检索、图结构、用户画像组合。价值是把"写记忆"从纯 prompt 技巧下沉为可复用模块。它与 MemGPT 可互补：前者管抽取与存储管线，后者管上下文与外存之间的搬运策略。
- **记忆的反思与整合**：反思是周期性让模型回顾轨迹，生成更高层见解；整合是把多条低层记录合并成稳定条目，减少冗余与冲突。
- **记忆图谱**：实体—关系—实体存储，检索用图遍历 + 向量。强在多跳推理与结构化关系；弱在构建成本高、实体对齐难。

### 7.7 生产挑战与检查表

| 维度 | 典型问题 | 缓解手段 |
|---|---|---|
| 隔离 | 检索串租户 | 元数据强制过滤 + 单测覆盖 |
| 持久化 | 索引损坏、迁移失败 | 备份、双写过渡期、回放重建 |
| 一致性 | 摘要与向量矛盾 | 版本号、主库为准、对账任务 |
| 隐私 | PII 进向量库 | 脱敏、加密、分级存储、可删除 |
| 性能 | 召回慢 | 预过滤、缓存 embedding、减小候选集 |
| 安全 | 恶意记忆污染 | 信任分级、人工审核通道、红队 |

**最容易出的事故**：租户隔离失败、把敏感数据写入长期记忆未加密。

**Embedding 模型升级后旧向量怎么办**：旧向量与新模型不在同一空间，**不可混比**。做法：双写期（新旧模型并行写）、离线全量重嵌入、检索时标明 `embedding_model_version`，保证查询与库版本一致。

**多 Agent 共享记忆要注意什么**：区分共享语义知识（可读多 Agent）与私有工作记忆（单 Agent）；写权限要审计；避免一个 Agent 写入污染全局记忆——可用命名空间、审批流、置信度门槛。

---

## 8. 多智能体系统（Multi-Agent Systems, MAS）

定义：多个相对独立的 Agent（通常每个绑定不同角色、工具或策略）在某种**协作协议**下共同完成复杂任务。与"一个通用大模型 + 长提示词"相比，多 Agent 强调**分工、通信、状态与治理**。

### 8.1 单 Agent 的瓶颈 vs 多 Agent 的优势

| 现象 | 通俗说法 | 技术含义 |
|---|---|---|
| 注意力漂移 | 提示词越长，模型越"抓不住重点" | 长上下文下关键约束与中间推理步骤被稀释；模型对中间段落的有效利用弱于首尾（lost in the middle 类问题） |
| 推理链断裂 | 一步想太多，后面忘了前面 | 复杂任务需要多步规划与回溯；单轨迹里若缺少外部结构化记忆，易自相矛盾或跳步 |
| 能力局限 | "一个全能选手"往往样样稀松 | 单一 system prompt 难以同时覆盖严谨规划、创意发散、代码执行、合规审查；工具权限也难以"全开"而不失控 |

多 Agent 的三个优势：**专业化分工**（每个子 Agent 职责窄而深，提示词与工具集更小更稳）、**并行处理**（无强依赖的子任务并行，缩短墙钟时间）、**容错与隔离**（某子 Agent 失败可重试、替换实现或降级）。

**什么时候该上多 Agent**：任务可分解、需要不同专业视角、需要并行、需要权限隔离（例如代码执行与对外发布分离）、需要可观测的分阶段产出。单 Agent 适合任务边界清晰、工具少、强实时、成本极度敏感的场景。注意：传统多 Agent 通常 Token 与调用次数上升，但若通过小模型子任务 + 大模型仲裁、并行缩短时间、减少无效重试，总成本未必更高，需要按业务度量。

你之前记过的那篇 AgentFlow 笔记里，就是典型的多 Agent 分工：**计划员 + 操作工 + 质检员 + 出报告的人**，并且强调"让计划员边干边学"（in-the-flow 优化）。这属于多 Agent + 反思的组合形态。

### 8.2 三大协作模式（PDF 术语）

1. **静态链（Static Chain）**：角色与顺序预先写死，适合稳定 SOP。
2. **动态链（Dynamic Chain）**：由模型决定下一步交给谁，适合开放域任务，但要防循环与成本失控。
3. **条件式/分支式**：根据中间结果决定是否进入某个 Agent（如风险评分超阈值才走人工复核）。

### 8.3 通信机制

子 Agent 之间传递的应是**结构化消息**（JSON / 状态对象），而不是堆砌自然语言——这样能减少歧义、便于程序校验，也让"中间结果结构化"成为缓解注意力漂移的手段。

### 8.4 冲突解决与状态同步

- **冲突解决**：优先级规则（权威源 > 时间新 > 多源一致）、让模型显式输出"冲突说明"、必要时触发人工。
- **状态同步**：区分共享语义知识与私有工作记忆；写权限审计；用命名空间、审批流、置信度门槛防污染。

### 8.5 主流多 Agent 框架与协议

框架层面 PDF 点名了 **AutoGen / CrewAI**（多 Agent 协作与角色扮演类），并有 LangGraph 状态机一节（LangGraph 用**图/状态机**表达 Agent 流程，比线性 Chain 更适合带分支、循环、检查点的多 Agent 编排）。

**A2A（Agent2Agent）协议**——这是联网核对到的重点，值得单列：

- 由 Google 于 2025 年 4 月发布，现由 **Linux Foundation 的 Agentic AI Foundation** 治理（A2A 与 MCP 现在同一基金会下）。
- 定位是**代理间通信层**：让不同框架（LangGraph、CrewAI、Google ADK、Semantic Kernel、AutoGen 及自研实现）构建的 Agent 能互相发现能力、委派任务、协同工作，而不需要为每一对组合写定制集成代码。
- 关键机制：**Agent Card**（在 `/.well-known/agent-card.json` 等处发布，描述能力与技能、认证要求、端点、是否支持流式）；**任务生命周期状态**（Submitted / Working / Input-required / Auth-required / Completed / Failed / Canceled / Rejected）；支持**多种协议绑定**（JSON-RPC、HTTP+JSON、gRPC），核心操作如 `SendMessage`、`SendStreamingMessage`。
- 生态状态：到 2026 年 4 月，**超过 150 家组织**支持 A2A，官方 SDK 覆盖 Python、JavaScript、Java、Go、.NET、Rust；Microsoft Foundry、Amazon Bedrock AgentCore、Google Vertex AI 等云平台已支持（各平台支持的协议版本、传输方式、模态与发布状态不完全相同）。
- **与 MCP 的关系**：两者互补，是两层——**MCP 是 agent-to-tool（智能体到工具）层，A2A 是 agent-to-agent（智能体到智能体）层**。典型编排形态是：用户 → 编排 Agent（A2A 客户端）→ 若干专家 Agent（A2A 服务端）→ 各自通过 MCP 访问数据库、API、邮件、日历等工具。

来源：<https://docs.mintmcp.com/blog/a2a-protocol-explained>；<https://www.linuxfoundation.org/press/a2a-protocol-surpasses-150-organizations-lands-in-major-cloud-platforms-and-sees-enterprise-production-use-in-first-year>。

---

## 9. 大模型基础（速览）

> 这一节按标准知识写。你的资料库 PDF 里第 07 模块（Transformer、Attention、KV Cache、LoRA、RLHF/DPO，共 28 题）我这次只读到目录，没有逐段读取正文，所以下面不是对你那份 PDF 的转述。

理解 Agent 只需要抓住几件事：

- **Transformer**：基于自注意力机制的序列建模架构，是当前主流 LLM 的底座。它的核心计算可以并行处理整个序列，这也是大模型能规模化的前提。
- **Attention（注意力）**：让每个位置根据相关性聚合其他位置的信息。理解它的**成本结构**很关键：注意力开销随序列长度快速增长，这直接解释了为什么"上下文越长越贵越慢"。
- **KV Cache（键值缓存）**：自回归生成时，把已计算过的 Key/Value 缓存下来，避免每生成一个 token 都重算整个前缀。它是推理加速的基础机制，也是长上下文推理显存占用的主要来源之一。**工程含义**：多轮对话中缓存复用能显著降本，但缓存失效（如 system prompt 改动）会导致重算。
- **LoRA（Low-Rank Adaptation，低秩适配）**：冻结原模型权重，只训练少量低秩矩阵来适配下游任务，大幅降低微调参数量与显存需求。**工程含义**：多个 LoRA 适配器可以按租户/业务热插拔。
- **RLHF / DPO**：RLHF 是"人类反馈强化学习"（用人类偏好训练奖励模型，再据此优化策略）；DPO（Direct Preference Optimization）跳过显式奖励模型，直接用偏好数据优化策略。**与 Agent 的关系**：它们决定了模型的**指令跟随与工具调用对齐质量**，是 Agent 可靠性的地基。

**Prompt 与微调的关系**（高频对照题）：Prompt 是推理时上下文策略；微调是改权重。数据少、迭代快优先 Prompt + 评测；要固化领域行为、长期一致再考虑微调；二者常组合。

---

## 10. 工程化实践

### 10.1 模型路由

**为什么路由**：路由不仅是性能问题，也是合规问题（不同数据不能出同一模型/同一地域）。

常见优先级调度策略：

| 策略 | 含义 | 适用 |
|---|---|---|
| 固定优先级 | 列表顺序尝试 | 简单可靠 |
| 成本优先 | 在满足质量阈值下选最便宜 | 批处理、非实时 |
| 延迟优先 | SLA 内选最快 | 交互式产品 |
| 负载感知 | 结合队列深度、429 率动态调整 | 大规模生产 |
| 任务分类路由 | 代码类→A，摘要类→B | 提高性价比 |

关键认知：**优先级不是"永远用大模型"，而是在约束下最优化**（成本、延迟、质量、可用性）。

### 10.2 三态熔断器（Circuit Breaker）

类比电路保险丝：失败太多就暂时断开，避免把下游打挂，同时给下游恢复时间。三个状态：

1. **Closed（闭合）**：正常转发请求；统计失败率/连续失败次数。
2. **Open（打开）**：失败超阈值，快速失败（不再调用下游），进入冷却。
3. **Half-Open（半开）**：冷却结束，放行少量探测请求；成功则回到 Closed，失败则回到 Open。

**与重试的关系**：熔断解决"系统性故障时雪崩"的问题，重试解决"偶发失败"的问题。两者要配合，但重试必须带上限与退避，否则会放大故障。

### 10.3 成本与性能

- **Token 优化**：摘要、小模型路由、缓存、批处理工具。
- **并发控制（Semaphore）设多大**：结合供应商 RPM/TPM、本机 CPU、下游工具容量；压测得到饱和点，略低于饱和并留余量；按租户分桶避免噪声邻居。
- **提示词压缩**：删冗余词、合并重复规则、用符号与结构化标签、定义缩写表。注意过度压缩可能增歧义，压缩后要回归测试。

### 10.4 可观测性与版本化

- **Trace / 日志至少记什么**：用户输入（脱敏）、模型原始输出、解析后的工具调用、工具返回摘要、耗时与 Token、版本号（模型与 Prompt）、追踪 ID。便于复盘与合规审计。
- **为什么 Agent 更需要 Prompt 与模型的版本化**：行为会随 Prompt/模型悄悄变化，导致线上回归难定位；版本化可与 Trace、评估集、回滚策略一一对应。
- **发布策略**：Prompt/工具/schema 版本化；影子模式（只记录建议不执行）；金丝雀（小流量用户群）；关键指标对比（成功率、成本、违规数）；一键回滚。

### 10.5 评估与治理

**怎么评估一个 Agent 的好坏**：分层评估——任务成功率、平均步数/成本、工具错误率、用户满意度、安全事件数；基准可包括静态数据集 + 仿真环境 + 线上 A/B。

**怎么测试 Agent**：单元测工具、模拟环境、回归集（固定任务与期望轨迹范围）、对抗用例（注入、越权）、线上金丝雀。**避免只测最终答案而忽略过程正确性**。

**三大挑战与对策**：

| 挑战 | 具体表现 | 对策 |
|---|---|---|
| 幻觉 | 编造事实或工具参数 | 检索 grounding、约束解码、验证器 |
| 安全 | Prompt 注入、工具滥用、数据外泄 | 分层信任域、输出过滤、秘密不入模型上下文 |
| 成本 | 长链路 × 大上下文 | 摘要、小模型路由、缓存、批处理 |
| 可解释性 | 说不清为什么这么做 | 轨迹、引用、决策日志，关键操作可回放 |
| 评估困难 | 单轮 BLEU 不适用 | 过程指标（工具是否正确）+ 结果指标（任务是否完成）+ 人工抽检 |

**Agent 的最大风险**：**复合错误与权限滥用**——单步小错在多步放大，或工具被诱导执行高危操作。必须最小权限 + 强审计 + 人在回路。

**怎么做"人在回路"又不打断体验**：分级——低风险自动执行；中风险异步审批；高风险实时确认。产品上做预授权（例如仅本次会话可读某目录）、可撤销、默认最小权限。

---

## 11. Prompt 工程

### 11.1 定义与定位

**Prompt Engineering（提示词工程）**：通过设计、组织、迭代输入给 LLM 的文本（以及配套参数与格式约束），使模型在任务理解、推理质量、输出格式、安全性等方面达到预期效果的实践技术。它不是"写几句咒语"，而是把业务目标翻译成模型能稳定利用的上下文与指令。

原理：LLM 本质是"在给定前文条件下预测下一个 token"；你提供的 Prompt 会强烈偏置后续生成的概率分布。在 Agent 场景中，Prompt 还承担**角色定义、工具使用规范、错误恢复策略**等职责。所以有人把它叫 Agent 的**软代码**——变更 Prompt 像改业务规则，需要版本管理与评审。

### 11.2 五段式结构

| 组成 | 含义 | 典型写法 |
|---|---|---|
| 角色 | 模型以什么身份作答 | "你是一名资深数据分析师……" |
| 任务 | 具体要完成什么 | "请根据下列工单判断优先级并说明理由" |
| 上下文 | 事实材料、用户状态、检索片段 | "以下为知识库片段：……" |
| 输出格式 | JSON / XML / 字段列表 / 步骤 | "仅输出 JSON，键为 …" |
| 约束 | 禁止项、长度、风格、安全 | "不得编造链接；不超过 200 字" |

**最容易忽略的是格式与负向约束**（不要做什么）。没有格式，程序难接；没有约束，容易啰嗦、幻觉或越权。

### 11.3 设计四原则

- **清晰（Clear）**：用词准确无歧义，避免"它、这个、上面"指代不明。反例："把日期改一下格式。"正面："将日期统一转换为 ISO 8601 格式（YYYY-MM-DD）。若无法解析某日期
