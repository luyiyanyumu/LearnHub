# 本地文档扫描与 OCR

LearnHub 提供两种本地能力：`document-structure` 使用 PP-StructureV3 进行逐页版面、文字、表格及公式识别；`document-ocr` 使用 Tesseract 提供基础中英文文字 OCR。**配置 `document-structure` 后，PDF 与栅格图片默认使用高精度逐页扫描，包括已有文字层的 PDF。** Word、Excel、PPT、Markdown、纯文本及源码仍使用对应格式的原生解析器；Word 等格式中的嵌入图片需要识别时，媒体阶段优先调用已配置的本地高精度模型提取可见文字。

基础部署不自动启动这两个可选侧车；未配置结构化扫描时，仍可使用原有文字层解析及已配置的基础 OCR。配置扫描服务但服务暂不可用时，报告保留失败或待处理状态，不把文字层结果冒充扫描成功。后端把 PDF 页渲染成 PNG，侧车只接收单张 PNG，不解析 PDF、Office、文件路径或 URL；抽取证据保留实际来源。

基础 Tesseract 在 CPU 上运行；高精度扫描支持 `cpu` 与 `gpu:0` 两种设备，GPU 路径已通过合成页面验收。两者都不需要 API key。镜像构建时下载依赖与模型，构建完成后识别不依赖外网。Tesseract 侧车的系统 OCR 和语言包在镜像中安装，Pillow 固定为 `13.0.0`。资料抽取的统一 Markdown、Office 图片、公式图片模型、定位 JSON 与 ZIP 导出见 [资料统一 Markdown](document-markdown.md)。可选媒体识别另使用「公式原图识别」模型档案，若配置为外部服务则会向该服务发送待识别图片，不能将其称为离线能力。

## 基础文字 OCR 的启用与关闭

已有源码部署在仓库根目录执行以下命令；每条命令都应保留当前部署正在使用的其他 `-f` 覆盖文件。首次部署先按主部署说明启动基础栈。新阅读器界面也需要构建并应用前端：

```sh
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.document-ocr.yml build document-ocr backend frontend
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.document-ocr.yml up -d --no-deps --wait --wait-timeout 90 document-ocr
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.document-ocr.yml up -d --no-deps backend frontend
```

覆盖文件向后端注入 `LEARNHUB_DOCUMENT_OCR_URL=http://document-ocr:8083`。上述命令先确认侧车健康，再只更新 OCR、后端和前端，不重建数据库、不操作业务数据卷。发布镜像部署也可配合 `docker-compose.release.yml`，但需要通过既有发布更新流程使用包含 OCR 接入代码和阅读器报告界面的应用版本；只对 `document-ocr` 执行源码构建，不对没有 `build` 定义的发布应用服务执行 `build`。

服务只暴露容器内的 8083 端口，没有宿主端口。后端与侧车通过独立的 `internal` Docker 网络通信，侧车不连接默认应用网络，也没有用户资料卷、密钥、配置目录或 Docker socket 挂载。运行用户为 `10001:10001`，根文件系统只读，`/tmp` 使用有容量限制的 tmpfs，移除 Linux capabilities 并禁止提权。

基础镜像默认来自 Docker Hub。如所在环境需要已有镜像代理，可在独立构建时显式设置：

```sh
docker build --build-arg DOCKER_REGISTRY=docker.1ms.run -f tools/document-ocr/Dockerfile -t learn-hub-document-ocr:local .
```

若 Python 包下载受限，还可显式设置构建参数 `PIP_INDEX_URL` 为维护者信任的包镜像；依赖版本仍由 `requirements.txt` 固定，不在运行时安装。

停用时，用原来的 Compose 文件组合重建后端容器，去掉这个覆盖文件所注入的 OCR URL；再单独停止 `document-ocr`。不需要删除业务数据卷。

## 应用导入、阅读与检索

配置结构化扫描时，所有非空 PDF 页和栅格图片都按页扫描，文字型 PDF 也不会跳过扫描。PDF 优先以 300 DPI 渲染；大尺寸页面选择满足单页像素预算的可用分辨率，并把识别坐标换算回原件页面。空白页可直接记录为空白证据。只有未配置结构化扫描时，才沿用文字层优先、无可用文字层时调用基础 OCR 的方式。

原始识别文本、清理文本、置信度、页码、矩形及实际来源保存在抽取证据中，合格文本才进入资料正文与索引；低置信度、乱码等异常文本会被拒绝进入索引，并计入报告。基础文字 OCR 的块置信度阈值为 `0.65`。原图和拒绝块仍供核对，不把可疑结果当作可靠正文。

基础清洗统一换行并修复明确的编码乱码，保留公式、表格和代码的结构边界。至少三页、覆盖六成已处理页面的重复短段落，仅在坐标明确落于页顶或页底 10% 区域时标为页眉/页脚候选并排除索引；候选原文和坐标仍保留在报告中。相同文字出现在正文区域时保留，不用文本重复直接删除定义或表头。该规则不是完整的版面语义识别。

资料阅读器显示持久化抽取报告、处理来源、质量警告与可用定位信息。读取报告不会重新执行 OCR。应用接口为：

- `GET /api/files/{id}/extraction`：读取已有抽取证据与质量报告。
- `POST /api/files/{id}/source-locations`，正文 `{"text":"检索证据片段"}`：把已接受正文中的匹配片段映射到原文页码与矩形；拒绝块不作为检索定位依据。
- `POST /api/files/{id}/extraction-jobs`：在后台抽取正文。对有页级报告的 PDF 或扫描图片，传入 `{"resume":true}` 可继续剩余页；可选 `force:true` 允许完整 HQ2 候选按下述规则更新旧自动正文，默认不启用。
- `GET /api/files/extraction-jobs/{jobId}`：读取任务状态、当前阶段、当前原文页、合格页数和总页数。扫描进度与已发布正文的可用页数分开显示；总页数未知时只显示阶段。
- `POST /api/files/{id}/reextract`：保留同步重新抽取接口。批量旧资料使用 [Markdown 迁移任务](document-markdown.md#历史迁移部分完成与重试)。
- `GET /api/files/{id}/extraction-page-image?page=N&sourceRevision=SHA256`：返回栅格原件对应帧的 PNG，避免多页 TIFF、GIF 动画或浏览器 EXIF 旋转使定位坐标错位；来源版本不匹配时拒绝旧定位。上传原件及下载内容保持不变，PDF 继续使用原文件和 PDF 阅读器。

证据同时绑定原文件 SHA-256 和接受正文摘要。文件或正文变化后，旧定位不再作为当前证据返回，需要重新抽取。PDF 应用定位使用页坐标，图片定位使用像素；它们与下文侧车响应的输入图片像素坐标不同，后端负责按渲染尺寸换算。

重新抽取得到合格正文后，沿用既有异步增量索引流程。向量检索需要另外配置嵌入服务并等待索引完成；未配置向量时，接受的正文仍可用于关键词检索。侧车健康、抽取成功与向量索引完成是三个不同状态，不能互相替代。

资料列表中的「继续扫描」或「继续抽取正文」每次处理一批，优先补未处理的页面，再尝试此前没有通过校验的页面。普通续跑保存可用页的正文、Markdown 和定位；旧结果升级为高精度扫描时，新候选分批保存，扫描尚未达到替换条件时继续使用旧正文与定位，不提前替换或缩短现有检索覆盖。报告中的 `pendingUpgrade` 与 `scanProgress` 表示待发布的新扫描，不能把它们的页数当作当前索引覆盖。继续操作依据新扫描的剩余页，而不是旧结果已覆盖的页数。

单批时间、页数或像素预算耗尽时可继续处理，达到单份资料累计容量上限时可拆分原件后继续收录。正文容量由旧版 400,000 字符提高至 800,000 字符；同原件、同管线版本的旧候选若有明确的 `character_limit` 截断页，继续扫描会优先重扫该整页，保留其他完整页，并在成功补齐后清除已解决的字符容量标记。原始文本或块数限制没有明确边界时不会猜测已完成页，也不自动提高其他预算。仍有低置信度块或公式需要检查时，可打开「抽取报告」核对。新结果通过替换检查并发布后，才更新正文、定位和索引。

资料上传的单文件上限为 1000 MiB。下表区分单次抽取和单份资料的累计上限。超过抽取大小上限的文件可上传并保留原件。

| 基础抽取任务（未配置结构化扫描） | 上限 |
| --- | --- |
| 原文件 | PDF 200 MiB，其他格式 64 MiB |
| 整份任务时间预算 | 120 秒 |
| PDF 页面 | 每批最多 500 页；时间、字符和块数预算仍独立生效 |
| OCR 调用页面 | 20 页 |
| 单页渲染/图片像素 | 12,000,000 |
| 单任务累计像素预算 | 80,000,000 |
| 接受正文 | 每份资料累计 800,000 字符 |
| 抽取块 | 每份资料累计 20,000 |
| 原始文本保留 | 每份资料累计 800,000 字符 |

达到上限、超时或有页面/块被拒绝时，报告明确标出警告、`partial`/`truncated` 及已处理数量，不静默把未处理部分标成完整成功。侧车的 12 MiB/16,000,000 像素/60 秒限制针对单次 PNG 请求，不能据此提高上述整份资料预算。

本地 Tesseract 首版只提供文字段落和基础分栏结果，不赋予标题角色，也不恢复表格语义或公式 LaTeX。统一 Markdown 的媒体阶段可另用现有图片模型转写公式、文字和图像描述；它与本地 OCR 使用独立配置、缓存与质量门控。模型未配置、结果不确定或达到预算时保留原图及待处理说明，可单独重试媒体，不把待核对提示加入检索正文。

## 默认高精度逐页扫描

使用 `deploy/docker-compose.document-structure.yml` 作为额外的 Compose 覆盖文件，可启用独立的 PP-StructureV3 侧车。覆盖文件向后端配置 `LEARNHUB_DOCUMENT_STRUCTURE_URL`；配置后即成为 PDF 与图片的默认抽取方式，不需要每份文件额外选择。镜像构建时安装固定版本推理依赖并下载、校验模型权重；GPU 镜像沿用相同源码和模型，替换为 CUDA 推理运行时。运行时只通过内部网络接收逐页 PNG，无外网依赖，也不挂载资料目录。

| 识别阶段 | 模型 |
| --- | --- |
| 版面检测 | `PP-DocLayout_plus-L` |
| 文字检测 | `PP-OCRv5_server_det` |
| 文字识别 | `PP-OCRv5_server_rec` |
| 公式候选 | `PP-FormulaNet_plus-L` |
| 规则表格 | `SLANet_plus` |

基础结构化扫描覆盖文件给侧车配置 4 个 CPU 配额和 6 GiB 主机内存上限；依赖包版本保持锁定。GPU 部署在该文件之后叠加 `deploy/docker-compose.document-structure-gpu.yml`，使用匹配的 GPU 镜像和 NVIDIA 容器环境，并设置 `LEARNHUB_STRUCTURE_DEVICE=gpu:0`。健康响应以 `profile=high-quality-v2`、`version=3.7.0-hq2`、模型名单及实际 `device` 标识所用配置，不能只凭服务名称或覆盖文件存在判断 GPU 已启用。

同一覆盖文件将后端容器内存默认限制为 6 GiB，避免长资料连续扫描时 JVM 按整台主机内存扩展堆、挤占扫描服务与桌面程序。现有默认 `JAVA_OPTS=-XX:MaxRAMPercentage=75` 在此限制下允许约 4.5 GiB Java 堆，其余空间留给原生内存和线程。可用 `LEARNHUB_DOCUMENT_BACKEND_MEMORY` 调整容器额度；若另设固定 `-Xmx`，须同时确保堆小于容器额度并留出余量。

2026-11-12 已通过运行中的 GPU 服务 HTTP 接口复测 `3.7.0-hq2`（`high-quality-v2`）的两张合成中英文页面：单栏样本耗时 3.796 秒，归一化文本编辑距离为 `0 / 373`；轻微失真双栏样本耗时 6.467 秒，编辑距离为 `1 / 354`。双栏样本的 16 个表格单元格和归一化公式均与参考答案匹配，两张样本的坐标均合法，识别质量与前版合成测试一致。这两张样本不含插图，图像区域保留路径另由 11 项单元测试覆盖；本次未重新测量初始化时间或内存。这些结果不是实际资料准确率或持续吞吐承诺。完整环境、各版本测量与验收范围见 [扫描侧车验证记录](../tools/document-structure/README.md#合成样本验证记录)。

CPU 模式的 6 GiB 配置只是资源上限：早期未限制文字检测尺寸的候选曾内存不足；当前限制检测尺寸的 CPU 候选尚未完成完整页面验收。GPU 结果不能代替 CPU 的容量或性能验证，细节见 [模型与运行配置](../tools/document-structure/README.md#模型与运行配置)。

新上传与重新抽取的 PDF、图片都使用扫描管线 `document-ingestion-v5-scan-hq2`。阅读器「抽取报告」中的「重新扫描」可主动重做，「继续扫描」补齐未完成的新扫描。普通读取报告、打开原件、升级程序都不会自动批量重扫旧资料；已有资料由用户显式运行更新任务。

扫描任务按预算分批运行。大模型在 CPU 上比轻量模型耗时更多；首次镜像构建需下载约 1 GiB 模型文件。高精度是模型配置方向，不是逐页正确率保证。达到预算、识别出错或遇到无法核对的表格时，报告会标记待处理范围，不把任务结束等同于全部覆盖。

版面解析遗漏的原始识别行按坐标补回，表格结构不可靠时保留识别行。扫描检测到的插图与图表保留原始区域图片，其中的可见文字单独识别；文字结果不能替代原图。公式候选单独展示，核对原图并确认后才参与索引。旧文字层或旧 OCR 结果升级时，完整的新候选通过质量和覆盖校验前，原有可靠正文与定位继续可用。阅读器可切换「已发布正文」和「新扫描候选」查看各自报告；候选预览和公式确认不会把未发布页数伪装成当前检索覆盖。

已有正文的高精度重扫结果保留为候选，主「结构化 MD」默认展示最新扫描并标记待核对内容，当前检索正文保持已发布版本。完整候选需单独审核入库，`force:true`、继续扫描及最后一个公式确认都不代替审核。发布仍须满足同一原件、全部页面可用、无失败或被拒绝页、无截断及累计容量限制等条件，并保护已确认公式。低置信度新块仍不进入索引，质量问题继续在报告与任务中标为部分完成。原件和旧报告均保留。完整语义见 [历史迁移、部分完成与重试](document-markdown.md#历史迁移部分完成与重试)。

## 基础文字 OCR 的 HTTP 合同

`GET /health`：就绪返回 200；缺少 Tesseract 或任一所需语言包返回 503。

```json
{"ok":true,"ready":true,"engine":"tesseract","version":"5.3.0","languages":["chi_sim","eng","osd"]}
```

`POST /ocr`：`Content-Type: image/png`，请求体为 PNG 原始字节，必须提供单个 `Content-Length`。不接受任何 query、fragment、路径参数、JSON、URL、HTTP 身份凭据或自定义 OCR 参数。固定执行命令为：

```text
tesseract stdin stdout -l chi_sim+eng --psm 3 tsv
```

成功返回 JSON：

```json
{
  "engine":"tesseract",
  "version":"5.3.0",
  "width":2480,
  "height":3508,
  "blocks":[{
    "id":"p1-b1-p1",
    "type":"para",
    "text":"合成资料\nSynthetic document",
    "rect":[100,120,800,220],
    "confidence":0.93,
    "words":6
  }],
  "warnings":["ocr_text_only: formulas and table semantics are not reconstructed"]
}
```

坐标是输入图片中以左上角为原点的像素，`rect=[x0,y0,x1,y1]`，右下边界不包含在矩形内。不会根据 DPI、显示缩放或页面宽度重新换算。`words` 是 TSV 中非空 word 行的数量，中文字符也可能单独形成一个 word。

按 Tesseract 的 page/block/paragraph 首次出现顺序返回段落，不把全页按 y 坐标重新排序，以免交错双栏内容；保留段落内部的行换行。中文 token 及中文标点按中文习惯拼接，英文单词保留分隔。段落矩形为其识别词框的并集；置信度是词置信度按非空白字符数加权后的 0–1 值，不是经过校准的正确率。首版不推断标题和列表角色，`type` 均为 `para`。

没有检测到文字仍返回 200、空 `blocks` 和 `no_text_detected` 警告。错误响应为 `{"ok":false,"code":"...","error":"..."}`，不附带图片、文档正文或 Tesseract 原始错误输出。

| 场景 | HTTP | 典型 code |
| --- | --- | --- |
| 坏图、截断、动画、多帧、缺失/重复长度、query | 400 | `invalid_image` / `invalid_length` / `query_forbidden` |
| PNG 超过 12 MiB，或像素超过 16,000,000 | 413 | `image_too_large` |
| 请求媒体类型不是 PNG | 415 | `invalid_content_type` |
| 携带凭据 | 403 | `credentials_forbidden` |
| 两个处理槽均被占用 | 429 | `busy`，`Retry-After: 1` |
| 引擎或语言包不可用 | 503 | `engine_unavailable` |
| OCR 处理超过 60 秒预算 | 504 | `ocr_timeout` |
| 读请求体超时 | 408 | `request_timeout` |
| 引擎失败、非法输出或超过输出限额 | 502 | `ocr_failed` / `invalid_output` / `output_too_large` / `too_many_blocks` |

最多同时处理两个请求，不为第三个 OCR 请求排队。每次请求从接收图片起共用 60 秒处理预算，子进程使用剩余时间并在超时后终止；图片解码前检查尺寸，解码后也检查剩余预算。正文读取还有 5 秒无数据超时。TSV 和 JSON 分别不超过 16 MiB，stderr 不超过 64 KiB，识别文本不超过 1,000,000 字符、200,000 个词和 5,000 个段落，超限明确失败，不静默截断。

## 局限

这是文字 OCR，不是数学公式、表格或版面语义模型。公式上下标、分式、矩阵、表格单元格关系、合并单元格、脚注与阅读顺序可能识别错误；不会把这些结果自动伪装成可靠的 LaTeX 或结构化表格。标题角色、列表层级和跨页段落关系也需要后续判断。`--psm 3` 的自动分割还可能漏掉稀疏页面上的孤立短标题，正常返回或高置信度不代表页面每处文字都已覆盖。手写、倾斜、低分辨率、复杂背景、繁体及其他语言不在本版主要验收范围内。

300 DPI 有助于清晰扫描文字识别，但不是准确率保证。原图页、像素框和 OCR 来源应保留供用户核对，尤其是数字、单位、公式和表格。

## 合成数据验收

不使用本机笔记、资料、数据库或 API key。宿主 Python 有 Pillow 时可先执行单测：

```sh
python -B -m unittest discover -s tools/document-ocr -p "test_*.py" -v
```

镜像内使用锁定依赖进行完整单测与真实中英 OCR HTTP 烟测，无需外网、宿主端口和挂载：

```sh
docker build -f tools/document-ocr/Dockerfile -t learn-hub-document-ocr:verify .
docker run --rm --network none --read-only --tmpfs /tmp:size=256m,mode=1777,noexec,nosuid,nodev --cap-drop ALL --security-opt no-new-privileges --memory 1g --cpus 2 --pids-limit 64 learn-hub-document-ocr:verify python -B -m unittest discover -s /app -p "test_*.py" -v
docker run --rm --network none --read-only --tmpfs /tmp:size=256m,mode=1777,noexec,nosuid,nodev --cap-drop ALL --security-opt no-new-privileges --memory 1g --cpus 2 --pids-limit 64 learn-hub-document-ocr:verify python /app/smoke.py
```

单测覆盖中文标点拼接、多栏与段落顺序、行换行、坐标并集、字符加权置信度、坏图、透明图、动画、容量限制、真实子进程超时/输出上限和 HTTP 忙碌响应。烟测在容器内用 Noto CJK 字体生成清晰的中英图片，通过真实 HTTP 和 Tesseract 识别，核验文字、坐标、版本、语言包与坏输入响应。该烟测是集成验收，不代表真实扫描文档准确率基准。

2026-10-30 验证记录：Pillow `13.0.0`、Tesseract `5.3.0`（`chi_sim`、`eng`、`osd`），容器内 28 项单测通过；真实 HTTP 烟测的四行合成中英文字精确匹配，返回 2 个段落块，坐标与置信度合法，坏输入按合同拒绝。测试容器禁外网、无数据挂载、非 root、根目录只读，没有操作业务服务。

Tesseract 的 TSV 层级及 `--psm 3` 行为参见[官方命令行说明](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html#tsv-output)。
