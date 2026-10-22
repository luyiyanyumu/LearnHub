# 本地知识模型验证服务

这是实际运行 CUDA 4-bit QLoRA 的验证版：`Qwen/Qwen2.5-1.5B-Instruct` 基础模型加本地 LoRA 适配器。回答与闭卷评测只输入问题，不检索资料，不把原文附在提示词中，不调用云推理或读取 API 密钥。需要 NVIDIA CUDA 显卡；CPU 模式不会冒充 GPU 训练成功。

默认服务与 CLI 的正文、SQLite 队列、适配器、训练日志、逐题回答与报告都保存在项目根目录被忽略的 `output/knowledge-model/runtime/`。基础模型、venv 仍位于其父目录 `output/knowledge-model/` 下，独立 smoke 实验使用 `--data-dir` 指定目录；请勿提交这些目录。示例与单元测试只含合成事实。

## 安装和启动

建议独立 Python 3.12 环境，先安装与本机驱动匹配的 CUDA PyTorch，再安装 `requirements.txt`。本机验证采用 PyTorch 2.8.0 / CUDA 12.8；依赖区间允许后续安全更新，复现实验时应另外保留本机 `pip freeze`。

```powershell
python -m venv output/knowledge-model/venv
output/knowledge-model/venv/Scripts/python.exe -m pip install torch==2.8.0 --index-url https://download.pytorch.org/whl/cu128
output/knowledge-model/venv/Scripts/python.exe -m pip install -r tools/knowledge-model/requirements.txt
$env:KNOWLEDGE_MODEL_BASE_MODEL = (Resolve-Path output/knowledge-model/models/qwen2.5-1.5b-instruct).Path
output/knowledge-model/venv/Scripts/python.exe tools/knowledge-model/main.py serve
```

模型目录须事先下载完整的 Hugging Face 原始权重与 tokenizer（不是 GGUF）。也可用 `--model-path` 显式指定。指定现存本地目录时，模型加载使用 `local_files_only=True`。服务启动只创建队列，不加载 GPU；端口固定默认 `127.0.0.1:8766`，不应通过端口转发暴露公网。

默认序列长度 512、microbatch 1、梯度累积 4、LoRA rank 16、学习率 0.0003、80 个优化步。用 `--steps`、`--max-length`、`--accumulation`、`--learning-rate`、`--rank` 调整。桌面显存占用会影响可用容量，首次先用 2 步独立目录检查显存，再正式验证。

## 两轮真实训练实验

```powershell
output/knowledge-model/venv/Scripts/python.exe tools/knowledge-model/main.py smoke --model-path output/knowledge-model/models/qwen2.5-1.5b-instruct --data-dir output/knowledge-model/smoke-run --steps 80
```

此命令模拟两次普通正文上传，无需手写 facts 字段：第一轮学习两个站点事实；通过门槛后，第二轮学习两个新领域事实，同时回放并评测第一轮。每轮先按实际推理模式加载现有模型并评测，再卸载并加载训练实例。训练保存适配器后，释放训练实例，从磁盘按实际推理路径重新加载产物，最后闭卷评测并决定发布。基线与候选都不在启用训练准备的实例上评分，`artifact_verification` 记录产物重载核验。测试问句不作为训练样本，答案也不会出现在测试问题中；事实本身应在训练资料中出现。

只有新增事实准确率至少 80%、旧事实准确率较训练前下降不超过 10 个百分点、独立未知题拒答率至少 90%、已知题误拒率不超过 10%，才原子切换活动版本。缺少未知题或已知误拒评测同样不能发布。失败候选保留报告和适配器，活动模型不变。第一轮若失败，smoke 会停止并如实报告；增大步数需用新目录或重试任务。不要降低门槛来掩盖实验失败。

未知题同时按类型检查：各有题类别也须达到拒答门槛，整体平均达标不能掩盖自然缺失字段等弱项。严格回归可设置 `--new-min 1 --old-min 1 --old-drop-max 0 --unknown-min 1 --known-abstention-max 0`，要求本组新题、旧题、各类未知题全部正确，且无已知题误拒；`--old-min` 是绝对旧题下限，避免训练前已有错误但相对没下降就误判为完全保留。

拒答同时进入训练和评测：训练与评测使用永久分离的合成实体及问句模板，包含陌生实体、强迫猜测和经全文排除的随机缺失字段。原失败探针只用于评测。小语料增加已有正例重复以平衡拒答比例；`coverage` 保留唯一正例数、重复数、未知样本数及采样限制。`abstention-bank.json` 保存实际两套题。此流程参考 [R-Tuning 的拒答训练思路](https://aclanthology.org/2024.naacl-long.394/)，本实现是本机小样本验证，并非该论文的完整复现。推理不读题库、正文或实体名单。

`candidate.unknown` 单列未知题拒答成绩，`candidate.known_abstention` 单列已知题回答规范“不知道”的比例；其他拒绝措辞计为已知题答错，但不计入此项误拒率。`all` 继续仅统计已知正例，不能用拒答样本抬高知识准确率。未知题只有简短“不知道”才计为正确，先说不知道又猜答案不算拒答。严格的新旧题 100% 门槛会拒绝任何错误或拒答的已知题。合成负例通过仍不能证明对真实资料未记载细节的泛化，需要另做未进入训练或发布门槛的最终审计。

`smoke-report.json` 是整体结果，`versions/<job-id>/` 中包含参数、语料快照、loss、显存峰值与逐题原始回答。新旧准确率只代表被测的少量事实。四个合成事实不能证明跨领域资料已被完整学会。

## 上传与评测覆盖

`POST /v1/documents`：

```json
{
  "source_id": "synthetic-station",
  "title": "合成站点资料",
  "revision": "1",
  "text": "澄岚站的校验码是 松柏47。\n琥珀桥的登记号=星沙82"
}
```

返回 `{job_id, duplicate, job}`，即使服务重启任务也保留。相同来源与其当前最新有效内容一致时去重；A→B→A 会创建新的训练任务。仅内容哈希不能区分“迟到的旧请求重发”和“用户有意恢复旧内容”，调用方应按来源顺序投递。队列串行运行，训练与推理使用同一显卡互斥锁。新上传不会改变正在训练的语料快照。

对 `键=值`、`某对象的属性是 某值` 这种显式陈述，服务确定性生成训练问句及不同措辞的评测问句。这是**局部事实记忆测试**，不是专家评测；普通长文、表格、推导和含糊语句可能没有可抽取题目。无法覆盖新资料时任务会训练并保存候选，但标记 `unvalidated`，不会虚构 100% 掌握率或自动发布。训练样本有数量上限，报告 `coverage.limited_coverage` 和实际样本数。

可以在同一上传结构提供 `facts`，或再次上传相同 `source_id`、正文以及新增的 `facts`，补充可靠评测后生成新任务：

```json
{
  "source_id": "synthetic-station",
  "title": "合成站点资料",
  "revision": "2",
  "text": "澄岚站的校验码是 松柏47。",
  "facts": [{
    "fact_id": "station-code",
    "statement": "澄岚站的校验码是松柏47。",
    "answer": "松柏47",
    "training_questions": ["澄岚站的校验码是什么？", "请给出澄岚站的校验码。"],
    "eval_questions": ["资料记录中，澄岚站使用哪个校验码？"]
  }]
}
```

`answer` 可另附 `aliases`。评分统一大小写、空白和普通句末标点，同时保留数值的小数点、正负号、百分号、比率等含义；`1.2` 与 `12`、`-5` 与 `5` 不会误判相同。不擅自换算单位、百分率或去掉代码前导零，可靠等价须由人工提供别名。原始响应始终保留，新指标名为 `numeric_safe_normalized_exact_answer`，分析工具按报告版本兼容早期计分。训练问句和评测问句规范化后相同、或测试问题包含答案时，API 会拒绝入队。原文包含完整评测问句的段落会从原文 CLM 训练中剔除，数量记录在 `coverage.raw_segments_removed_for_evaluation_isolation`，防止 FAQ 通过原文泄漏题面。请人工审查提取质量，尤其是数字、单位、否定句和多义术语。

人工审阅完整来源后，可提供 `abstention` 补充自然缺失字段题。训练保留各类合成负例的最低配额，再覆盖不同审核字段和实体对照，余量才补充问法变体。问句前缀变体不是独立字段，也不等于语义改写；字段数、变体数和实际覆盖分别记录。例如在上述合成资料中增加：

```json
{"abstention": {
  "training_questions": ["澄岚站的联系电话是多少？"],
  "eval_questions": ["澄岚站的验收日期是哪一天？"]
}}
```

只有核验来源确实没有答案、且不能从其他信息推断时，才应将题目标为未知；自动提取没找到不构成证据。自然拒答的训练字段与开发评测字段也应不同。正负题不可交叉冲突，人工拒答样本按独立类型分层选取，评测题面会从训练 QA 和原文过滤。最终外部审计题须另存文件，不加入 `abstention`；验证脚本只在所有轮次训练完成后将它们作为普通问题调用接口。

训练还会从既有正例构造 `entity_contrast`：只把训练问句中明确出现一次的实体换成永久训练专用、中性命名的陌生实体，其他条件完整保留，答案为“不知道”。推理不查询该实体名单。缺少元数据、绑定歧义、全文碰撞或评测题面重叠时跳过并记录，不会为了凑覆盖率猜测实体。`coverage.abstention_training.entity_contrast` 分别报告可用、入选和跳过数量；总体未知 QA 预算仍约 30%，预算不足必须显示有限覆盖。v5 只改变训练样本，开发未知题保持原样。

即使新资料通过门槛，旧资料中没有可测试事实的来源也不算验证通过。报告明确列出 `coverage.untested_source_ids` 和 `baseline/candidate.document_coverage`；发布只说明已测试事实达到门槛，不证明这些未覆盖来源没有遗忘。

## 本地调用

- `GET /health`：轻量存活检查，不加载模型。
- `GET /v1/status`：基础模型、活动版本、训练进度、队列数量。
- `GET /v1/jobs`、`GET /v1/jobs/{id}`：任务状态、前后新旧准确率和错误；不返回正文。
- `POST /v1/jobs/{id}/retry`：重试失败、拒绝或无评测覆盖的最新来源版本。
- `POST /v1/query`：`{"question":"澄岚站的校验码是什么？","max_tokens":64}`。
- `GET /v1/models`、`POST /v1/chat/completions`：兼容本地 OpenAI 风格调用，模型名 `learnhub-knowledge`，`stream:false`，仅接受一条 user 消息，以保持无文档上下文的查询约定。

推理返回 `answer`、`abstained`、`abstention_validated`、`model_version`、`knowledge_source:"model_parameters"`、`retrieval_used:false`。`abstained` 只描述模型原始回答是否为“不知道”，不会把猜测强行替换成拒答；`abstention_validated` 表示当前版本通过有限拒答题门槛，不能保证每道库外题都拒答。尚无发布版本时，明确返回 `model_version:"base"`。GPU 正在训练或回答其他问题时返回 HTTP 409；调用方稍后重试。

CLI 同时支持：

```powershell
python tools/knowledge-model/main.py enqueue --document output/knowledge-model/synthetic-document.json
python tools/knowledge-model/main.py train-next --model-path output/knowledge-model/models/qwen2.5-1.5b-instruct
python tools/knowledge-model/main.py status
python tools/knowledge-model/main.py query --question "澄岚站的校验码是什么？"
python -m unittest discover -s tools/knowledge-model -v
```

CLI 的 `train-next` 与服务使用相同的进程锁，不能同时运行在同一数据目录。中断训练会在下次启动恢复为排队，未完成候选不污染活动模型。重试保留任务 ID，递增 `attempt` 并原子移到队尾，语料截断随新序号更新，确保后来已发布模型的知识也参加旧知识回放和评测。重试的报告保存在原任务目录的 `attempt-N/`。更新来源只把最新正文加入回放语料，不能保证从参数中删除已经学会的旧信息。多来源累计资料超过采样预算时也不能保证全部旧知识得到回放。

实现依据：[Qwen 模型卡](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct)、[PEFT 量化训练](https://huggingface.co/docs/peft/developer_guides/quantization)、[bitsandbytes Windows / CUDA 安装支持](https://huggingface.co/docs/bitsandbytes/installation)。
