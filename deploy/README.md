# Docker Compose 部署

本目录提供 MySQL、LearnHub 后端和 nginx 前端的 Compose 配置。默认部署不包含 Ollama；未配置向量嵌入时，仍可使用关键词检索。

## 快速启动

先安装并启动 Docker Engine / Docker Desktop，确认 Docker Compose 可用。从仓库根目录执行：

```bash
cd deploy
cp .env.example .env
docker compose up -d --build
```

Windows PowerShell 使用 `Copy-Item .env.example .env` 复制配置模板。启动前按需填写 `.env` 中的数据库密码、端口和模型服务配置；`DEEPSEEK_API_KEY` 可选，也可以启动后在网页设置中添加模型档案。

打开 <http://localhost:8888>。首次构建需要下载镜像和编译前后端，耗时取决于网络和机器性能。运行 `docker compose ps` 查看容器状态；MySQL 和后端通过健康检查、前端处于运行状态后即可访问。

| 入口 | 默认地址 | 配置项 |
| --- | --- | --- |
| 网页 | `http://localhost:8888` | `WEB_HOST_PORT` |
| 后端接口 | `http://localhost:18080` | `BACKEND_HOST_PORT`；MCP 等客户端需同步修改地址 |
| MySQL | `localhost:3307` | `MYSQL_HOST_PORT` |
| 容器版 Ollama（可选） | `localhost:11434` | `OLLAMA_HOST_PORT` |

## 模型服务

在「设置 → 模型档案与分工」中管理服务地址、模型名、密钥和用途，再通过「模型分工」指定各任务使用的档案。对话档案用于对话、翻译、主题 wiki 等生成任务；向量嵌入档案用于知识库和图谱的向量索引。智能体会话也可单独选择对话档案。

启用语义检索的步骤：

1. 新增用途为「向量嵌入」的档案，填写服务地址和支持嵌入的模型。
2. 点击「测试」，确认嵌入接口可用及向量维数。
3. 在「模型分工 → 向量嵌入」中选择该档案。
4. 到知识库维护入口重建索引。

BGE-M3 和 Ollama 均非必需，也可使用其他兼容嵌入服务。未选择嵌入档案时不会请求嵌入服务，检索使用关键词。

更换服务地址、协议或模型后，需要重新生成知识库索引和图谱实体向量。旧向量会保留，但不会参与新嵌入空间的比较；索引重建失败时保留原索引。

### 使用宿主机上的 Ollama

先安装 Ollama，并下载需要的模型。以下模型名为示例，可替换为服务支持的模型：

```bash
ollama pull bge-m3
ollama pull qwen3:8b
ollama list
```

后端在容器内运行，访问宿主机服务时使用 `host.docker.internal`。Compose 已配置该主机名映射。

| 档案用途 | 提供方 | Base URL | 示例模型 |
| --- | --- | --- | --- |
| 向量嵌入 | 本地 Ollama | `http://host.docker.internal:11434` | `bge-m3` |
| 对话 / 翻译 / wiki | 本地 Ollama | `http://host.docker.internal:11434/v1` | `qwen3:8b` |

其他宿主机模型服务同样应使用容器可访问的地址。服务还需允许来自 Docker 网络的连接；只监听宿主机回环地址时，容器可能无法访问。

### 使用容器版 Ollama

在本目录执行：

```bash
docker compose --profile ollama up -d
docker compose exec ollama ollama pull bge-m3
docker compose exec ollama ollama pull qwen3:8b
```

模型保存在 `ollama-models` 具名卷中，重建容器后仍可使用。档案地址使用 Compose 服务名：

| 档案用途 | Base URL |
| --- | --- |
| 向量嵌入 | `http://ollama:11434` |
| 对话 / 翻译 / wiki | `http://ollama:11434/v1` |

如果宿主机已有服务占用 `11434`，先调整 `.env` 的 `OLLAMA_HOST_PORT`，再启动容器版；容器之间仍使用 `ollama:11434`。

### 接口与连接验证

Ollama 嵌入使用原生 `/api/embed`，档案地址的尾部 `/v1` 会自动归一化。对话使用兼容 OpenAI 的 `/v1/chat/completions`。其他嵌入提供方使用兼容 `/embeddings` 的接口，基址须包含服务要求的路径，例如 `/v1`。

可以在档案编辑界面点击「获取模型」查询服务支持的模型；列表只帮助选择，嵌入能力仍需通过档案测试确认。不提供模型列表的服务可以手动填写模型名。

「获取模型」会尝试修正完整接口地址、缺少 `/v1` 的基址，以及容器内无法连接的回环地址；成功修正后会在界面说明。需要鉴权的服务应配置相应密钥。

以下命令检查后端容器能否访问宿主机 Ollama，以及当前嵌入与索引状态。使用容器版时将第一条命令中的 `host.docker.internal` 换成 `ollama`：

```bash
docker compose exec backend curl -fsS http://host.docker.internal:11434/v1/models
curl "http://localhost:18080/api/kb/status"
```

### 兼容旧嵌入配置

模型分工中的「兼容旧嵌入配置」可继续使用历史配置。当前 Compose 会向后端注入以下环境变量：

| 环境变量 | Compose 默认值 | 用途 |
| --- | --- | --- |
| `KB_EMBED_BASE_URL` | `http://ollama:11434` | 嵌入服务地址 |
| `KB_EMBED_MODEL` | `bge-m3` | 嵌入模型名 |

修改 `.env` 后执行 `docker compose up -d backend` 应用配置。历史嵌入设置的优先级为数据库设置、外部配置、代码默认值；已有数据库覆盖值时，修改环境变量不会覆盖它。新部署建议直接创建嵌入档案并在模型分工中选择。

## 数据与备份

默认 Compose 项目名为 `learn-hub`，使用以下具名卷：

| 默认卷名 | 内容 |
| --- | --- |
| `learn-hub_mysql-data` | 笔记、资料元数据、知识库、图谱和设置 |
| `learn-hub_uploads` | 上传的资料原文 |
| `learn-hub_ollama-models`（可选） | Ollama 模型文件 |

覆盖 Compose 项目名后，卷名前缀也会变化。`docker compose down` 保留具名卷；`docker compose down -v` 会删除卷和其中的数据。

以下备份命令在 `deploy` 目录使用 Bash 执行。先暂停前后端写入，在容器内生成 SQL 文件，再复制出来，避免文本管道改变 BLOB 或文件编码。每条命令成功后再执行下一条：

```bash
docker compose stop frontend backend
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE" > /tmp/learnhub-backup.sql'
docker compose cp mysql:/tmp/learnhub-backup.sql ./backup.sql
docker compose cp backend:/app/backend/uploads ./uploads-backup
docker compose up -d
```

同时保存部署配置和自行修改的 `skills/`。发布版安装器会自动备份数据库、上传资料、技能和实例配置，详见 [npx 安装与更新](../docs/npx-install.md)。

恢复时使用与备份匹配的应用版本，并保持后端停止。数据库文件可按以下方式导入，上传资料需恢复到后端的 `/app/backend/uploads` 挂载目录。发布版安装器的完整恢复流程及 PowerShell 示例见 [npx 安装与更新](../docs/npx-install.md#手动恢复发布版备份)：

```bash
docker compose cp ./backup.sql mysql:/tmp/learnhub-restore.sql
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" < /tmp/learnhub-restore.sql'
```

### 从其他部署迁移

迁移时同时保留数据库和上传资料原文。先暂停源实例写入，完成备份，再将数据库导入目标实例、恢复上传目录，并核对笔记、资料和模型配置。

从宿主机运行迁入容器时，要检查模型和其他服务地址：容器内的 `localhost` / `127.0.0.1` 指向容器自身；访问宿主机使用 `host.docker.internal`，访问同一 Compose 网络中的服务使用服务名。

数据库向量是可重新生成的数据。使用 `--hex-blob` 导出可保留其二进制内容；如果向量损坏或嵌入服务、模型发生变化，确认嵌入档案可用后重建索引：

```bash
curl -X POST "http://localhost:18080/api/kb/rebuild"
curl "http://localhost:18080/api/kb/status"
```

## 运维与更新

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
docker compose restart backend
docker compose down
```

源码部署更新时，先备份数据库和上传资料，再从仓库根目录执行：

```bash
git pull
cd deploy
docker compose up -d --build
```

后端通过 Flyway 执行数据库版本迁移。新库自动初始化，已有 LearnHub 库从版本 0 接入并补齐兼容列和索引；迁移失败会阻止后端启动。后续表结构变化应新增 `backend/src/main/resources/db/migration/V<N>__<说明>.sql`，保留已发布迁移文件。详见 [数据库迁移说明](../docs/database-migrations.md)。

`docker-compose.release.yml` 使用版本镜像。安装器的 `adopt --from <原 deploy 目录>` 可登记现有实例并固定数据卷，再由 `update` 完成备份、迁移和健康检查。详见 [命令行安装器](../cli/README.md)。

### 端口或容器名冲突

在 `.env` 中修改端口和容器名，然后重新启动。独立实例应使用单独的部署目录；以下是该目录中 `.env` 的通用示例：

```dotenv
MYSQL_HOST_PORT=3310
BACKEND_HOST_PORT=18090
WEB_HOST_PORT=8890
MYSQL_CONTAINER_NAME=learn-hub-secondary-mysql
BACKEND_CONTAINER_NAME=learn-hub-secondary-backend
FRONTEND_CONTAINER_NAME=learn-hub-secondary-frontend
```

独立数据还需要独立的 Compose 项目名，例如执行 `docker compose --project-name learn-hub-secondary up -d --build`；后续运维命令也使用相同项目名。Windows 的 `start-all.bat` 会读取 `deploy/.env` 中的端口和容器名。

### 前端无法连接后端

查看前后端状态及日志，确认后端已启动。`nginx.conf` 使用 Docker DNS 在请求时解析 `backend` 服务名；后端暂时不可用时接口可能返回 502，恢复后重新请求即可。

## 可选 Milvus

默认向量存储为 MySQL。需要使用 Milvus 时，在本目录叠加 Compose 配置：

```bash
docker compose -f docker-compose.yml -f docker-compose.milvus.yml --profile milvus up -d
```

后端容器应通过 `http://milvus:19530` 连接服务。配置地址、切换后端并同步现有兼容向量：

```bash
curl -X PUT "http://localhost:18080/api/settings" \
  -H 'Content-Type: application/json' -d '{"milvusUri":"http://milvus:19530"}'
curl -X POST "http://localhost:18080/api/kb/vector/backend?backend=milvus"
curl -X POST "http://localhost:18080/api/kb/vector/sync"
curl "http://localhost:18080/api/kb/vector/status"
```

上述 curl 多行命令使用 Bash 语法。同步复用当前嵌入空间中已有的向量；更换嵌入服务或模型时，应重建索引。
