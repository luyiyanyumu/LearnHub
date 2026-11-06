# 本地文档 OCR（可选）

LearnHub 0.1.7 提供资料抽取的后端命令。可通过 CLI 或内置智能体检查抽取报告、下载 Markdown 和图片、查询处理任务及定位原文。调用方法见 [应用命令](../cli/README.md#应用操作与内置智能体)。

`document-ocr` 是 CPU 上运行的本地 Tesseract 侧车，识别简体中文和英文。基础部署和 npm 安装默认关闭此服务。构建镜像需要下载基础镜像、系统包和 Python 依赖。识别运行在隔离的容器网络中，无需 API 密钥或外网连接。

## 源码部署启用方式

先按 [Docker Compose 部署说明](../deploy/README.md) 启动基础服务。在 Windows PowerShell 或 macOS/Linux 终端中打开仓库的 `deploy` 目录，依次执行以下命令。已有其他 Compose 覆盖文件时，在每条命令里保留原来的文件顺序，并在后面加上 OCR 覆盖文件。

```sh
docker compose -f docker-compose.yml -f docker-compose.document-ocr.yml up -d --build --no-deps --wait --wait-timeout 90 document-ocr
docker compose -f docker-compose.yml -f docker-compose.document-ocr.yml up -d --build --no-deps --wait --wait-timeout 120 backend
```

- `docker compose` 根据两个 `-f` 指定的文件组合应用配置。`docker-compose.yml` 定义基础服务，`docker-compose.document-ocr.yml` 增加侧车，并给后端注入 `LEARNHUB_DOCUMENT_OCR_URL=http://document-ocr:8083`。
- `up` 创建或更新末尾指定的服务。`-d` 让容器在后台运行，`--build` 从当前源码构建镜像，`--no-deps` 将本次操作限于指定服务。`--wait` 等待服务通过健康检查，`--wait-timeout` 指定等待秒数。
- 第一条只启动并检查 `document-ocr`。成功后执行第二条，更新 `backend` 并确认后端健康。任一步失败时先停在该步检查日志。应用前端仍连接旧后端地址时，可通过启动器单独重启前端服务。

启用后，用应用命令 `document-extraction.capabilities` 检查 OCR 的 `configured` 和 `available`。停用时恢复原 Compose 文件组合，重建后端以移除 OCR URL，再单独停止侧车。业务数据库和资料卷继续使用原卷。

侧车仅在容器网络中监听 8083。后端通过独立的内部网络访问侧车，侧车以非 root 用户运行，使用只读根文件系统和有容量限制的临时目录。

## 资料抽取与检查

有可用文字层的 PDF 页面优先采用原文字层，扫描页按需渲染为 300 DPI PNG 后发送给本地 OCR。图片资料采用同样的识别质量检查。报告记录来源、原始及清理后文字、置信度、页码和矩形。通过质量检查的文字进入资料正文和检索索引，拒绝的片段仍可在报告中核对。

| 应用命令 | 用途 |
| --- | --- |
| `document-extraction.capabilities` | 检查 OCR 和图片模型配置及可用性 |
| `document-extraction.extraction` | 查看已有抽取证据、质量警告及处理来源 |
| `document-extraction.locate`、`document-extraction.source-page` | 定位证据片段，获取栅格原件对应页图 |
| `document-extraction.markdown`、`document-extraction.markdown-download`、`document-extraction.bundle`、`document-extraction.asset` | 读取抽取 Markdown，下载 Markdown、ZIP 或单张抽取图片 |
| `document-extraction.process-media`、`document-extraction.media-status` | 提交媒体处理，查看任务状态 |
| `document-extraction.migrate`、`document-extraction.migration-status` | 提交旧资料迁移，查看任务状态 |
| `file.reextract` | 显式重新抽取指定资料 |

查看报告会使用已保存的证据。需要更新旧资料时，可明确调用重新抽取或提交迁移任务。证据绑定原文件和接受正文的版本，原文变化后须重新抽取。内置智能体中的写入命令使用现有确认流程。

本地 Tesseract 提供文字段落与基础分栏。公式、表格结构、标题层级和复杂阅读顺序仍需核对原件。媒体处理可另用已配置的图片模型识别公式或补充图像描述。使用外部模型时，媒体请求会将待识别图片发送到配置的模型服务。

单次侧车请求只接收一张 PNG，最多 12 MiB、1600 万像素，处理时间上限为 60 秒。应用还限制整份抽取的时间、页数和像素预算。达到上限或拒绝部分页面时，报告会记录警告和实际处理范围。
