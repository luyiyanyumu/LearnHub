# 本地知识模型验证版

目标是验证知识是否进入模型参数：上传正文参与本地 QLoRA 训练，其他大模型通过 HTTP 向训练后的模型提问。推理与闭卷评测不检索资料，也不附原文。本版不是任意领域资料的无损转换器。

## 组成与状态

- LearnHub 上传成功抽取正文后，在同一数据库事务内落 `knowledge_model_outbox`；提交后后台发送。服务离线会保留任务并退避重试。
- Python 服务使用 SQLite 持久化训练队列、单 GPU 串行训练、历史样本回放、训练前后闭卷测试、候选版本和发布门槛。
- `/knowledge-model` 页面显示上传开关、队列、局部新旧知识准确率、未覆盖来源、失败重试及本地参数查询。
- `submitted` 只表示 Python 已接收；`published` 表示候选通过当前局部评测。`unvalidated` 表示没有足够可核验题目，不能理解为已掌握。

自动训练默认关闭。开启后只针对新上传和用户手动重抽的正文生效，不扫描已有资料。已有资料可以在新页面手动搜索入队。没有成功抽取正文的图片、扫描件等须先完成抽取。

## 本机启动

Windows 环境建议使用独立 Python 3.12、与驱动兼容的 CUDA PyTorch，以及可装入显存的 Qwen 指令模型。需要 `uv`，CUDA 驱动须支持对应 PyTorch wheel。初始化下载的是公开模型和依赖，不需要云推理密钥。

```powershell
./tools/run-knowledge-model.ps1 -Action Setup
./tools/run-knowledge-model.ps1 -Action Serve
```

默认监听 `127.0.0.1:8766`；模型、语料、数据库、适配器和报告均在 Git 忽略的 `output/knowledge-model/`。服务启动只建立队列，首次查询或训练才加载 GPU。训练与查询互斥，忙碌时返回可重试错误。

使用包含新迁移与代码的后端，访问 `/knowledge-model`，确认服务在线后开启“上传后自动训练”。后端设置键为 `knowledge_model.enabled` 与 `knowledge_model.base_url`，默认地址为 `http://127.0.0.1:8766`；只允许本机回环服务。

后端和 Python 服务须位于同一主机网络命名空间。容器内的 `127.0.0.1` 指向容器自身，Docker 后端不会自动连到宿主机服务；端到端验证应使用独立后端与合成数据库。

## 能力边界

这是实验性参数知识服务。局部训练评测通过不能证明完整掌握资料，也不能证明对陌生实体或资料未记载内容可靠拒答。没有足够可核验题目时标为 `unvalidated`；失败候选保留记录，不覆盖活动版本。使用合成资料验证训练、重启和遗忘行为，私有语料与逐题报告只保存在被忽略的 `output/`，不得提交。

## 验证上传、增量训练和遗忘

先运行纯训练实验，或在独立合成数据库的后端上运行真实上传实验。后一个脚本会开启该后端的上传训练并创建两份合成 TXT，切勿指向不准备写入测试资料的实例。

```powershell
./tools/run-knowledge-model.ps1 -Action Smoke -Steps 80
python tools/validate-knowledge-model.py --backend http://127.0.0.1:18081
```

上传实验第一轮学习两个合成事实，第二轮学习两个新事实并回放旧事实；每轮先检查当前模型，再训练候选、闭卷检查、通过门槛发布，并通过 Java 转发接口查询模型。报告默认保存到 `output/knowledge-model/api-validation-report.json`。

默认门槛是新题至少 80% 正确、旧题相对本轮训练前下降不超过 10 个百分点、独立未知题至少 90% 正确拒答、已知题误拒不超过 10%。没有旧题时显示不适用；没有新题或拒答评测时不自动发布。未知题必须只回答“不知道”，不能先拒答再猜测；全部回答“不知道”也过不了已知题门槛。新旧题都只覆盖可抽取的明确陈述，不代表整篇文档或其他领域的能力。报告保留逐题真实输出，失败不会伪装成通过。

训练期合成负例与评测期实体、问句模板永久分开，并过滤训练 QA 和原文段落中的评测题面。推理不检索原文、不查询题库或实体名单。页面分别显示未知题拒答率与已知题误拒率；后者只统计规范“不知道”，其他拒绝措辞计为答错但不计入此项。旧版没有这两项时显示未覆盖。严格验证将新题、旧题和每类未知题都要求 100% 正确，因此任何已知题答错或拒答都会阻止发布。

真实资料验证可以只读现有库、在忽略目录整理带原文证据的 `facts`，使用下面的通用本地实验脚本。外部审计文件在所有轮次结束后才读取，不送入训练，也不参与发布门槛。输入、原文证据、逐题私有回答和适配器必须留在 `output/`，文档和截图只使用合成例子。

```powershell
python tools/validate-knowledge-memory.py --documents output/knowledge-model/real-validation/real-documents.json --audit output/knowledge-model/real-validation/external-audit.json --output output/knowledge-model/real-validation/report.json
python tools/analyze-knowledge-memory.py --report output/knowledge-model/real-validation/report.json --output output/knowledge-model/real-validation/memory-summary.json
```

分析工具按来源、事实 ID 和题目严格对齐训练前后记录，分别统计新学会、原本就会、遗忘与补回的题数；未知题还按人工自然负例、合成缺失字段、陌生实体和强迫猜测分组。缺题、答案目标改变或指标不一致会报错，不会静默形成改善结论。真实资料的最终审计须另外查看，不能仅凭合成题的总体拒答率判断可靠性。

当前自动评测只为明确的 `键=值` 或“某属性是某值”生成不同措辞题目。其他正文仍可参加原文语言模型训练，但可能标为 `unvalidated`。训练样本和历史回放都有预算，页面与报告展示实际覆盖情况。更复杂的资料需要补充人工核验的问题与答案，详见 [Python 服务说明](../tools/knowledge-model/README.md)。

## 供其他模型调用

LearnHub 后端提供 `POST /api/knowledge-model/query`，请求为 `{"question":"澄岚站的校验码是什么？","max_tokens":48}`，返回包装在常规 `Result.data` 中。训练服务直接提供：

```http
POST http://127.0.0.1:8766/v1/chat/completions
Content-Type: application/json

{"model":"learnhub-knowledge","messages":[{"role":"user","content":"澄岚站的校验码是什么？"}],"max_tokens":48,"stream":false}
```

这个兼容接口只接受一条 user 问题；其他模型应把它注册为知识咨询工具，再将返回内容放入自己的工具结果。当前没有自动修改已有智能体的模型配置。`model_version`、`knowledge_source` 和 `retrieval_used:false` 用于核对调用的是哪个参数版本，不能作为事实真实性保证。

## 检查

```powershell
python -m unittest discover -s tools/knowledge-model -v
cd backend
mvn -Dtest=KnowledgeModelServiceTest,KnowledgeModelOutboxTransactionTest test
cd ../frontend
node --test src/utils/knowledgeModelView.test.js
npm run build
```

真实数据库事务测试需要显式配置隔离测试连接，未配置时跳过；不会自行访问个人资料库。训练中断后队列恢复；发布失败保留旧活动版本。撤销或删除原文不能保证从模型参数中移除相应知识。
