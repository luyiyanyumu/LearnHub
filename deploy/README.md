# 一键部署：docker compose

三条命令起全套（MySQL + 后端 + 前端 nginx）：

```bash
cd deploy
cp .env.example .env          # 填 DEEPSEEK_API_KEY（可选：不填只是 AI 功能不可用）
docker compose up -d --build
```

打开 **http://localhost:8888** 即可。首次构建要拉基础镜像 + 编译前后端，**约 5~15 分钟**；
之后改代码再 `up -d --build` 只要几十秒（依赖层有缓存）。

---

## 端口与入口

| 入口 | 默认 | 改哪 |
| --- | --- | --- |
| 网页（nginx） | http://localhost:8888 | `.env` 的 `WEB_HOST_PORT` |
| 后端接口 | http://localhost:18080 | `.env` 的 `BACKEND_HOST_PORT`（MCP server 默认连 18080，改了就同步改 MCP 配置） |
| MySQL | localhost:3307 | `.env` 的 `MYSQL_HOST_PORT` |
| 本地嵌入模型（可选） | http://localhost:11434 | `.env` 的 `OLLAMA_HOST_PORT` |

---

## 本地嵌入模型（语义检索要用，默认不开）

**默认三件套里没有 Ollama** —— 它镜像加模型约 2GB 起，而**不启用也能正常用**：
不启用时嵌入服务不可达，检索会退化成关键词检索（不报错，但"问什么都知道"的语义召回没有了）。

要语义检索就加上这个 profile：

```bash
docker compose --profile ollama up -d
docker compose exec ollama ollama pull bge-m3     # 约 1.2GB，只需一次（存在卷 ollama-models 里）
curl http://localhost:18080/api/settings/effective?keys=ai.embed_base_url,ai.embed_model
```

最后那条会把**生效值来自哪里**打印出来（`fromDb` / `fromExternal` / `effective`），确认嵌入地址是
`http://ollama:11434` 而不是默认的 `localhost:11434`：

```json
[{"key":"ai.embed_base_url","fromExternal":"http://ollama:11434","effective":"http://ollama:11434"}]
```

### 为什么嵌入地址必须由部署时给（而不是界面里选）

`EmbeddingClient` 走的是 **Ollama 的 `/api/embed`**（不是 OpenAI 兼容的 `/v1/embeddings`），
默认 `http://localhost:11434` + `bge-m3`；换嵌入模型必须**重建整个索引**，所以设置面板里
刻意没有这个入口（面板那行写着"嵌入向量固定用本地"）。容器里 `localhost` 指的是容器自己，
因此必须由 compose 注入服务名。三个可覆盖的键：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `KB_EMBED_BASE_URL`（或 `AI_EMBED_BASE_URL`） | `http://ollama:11434` | 嵌入服务地址；两个前缀都认（代码里的键叫 `ai.embed_base_url`，但语义属知识库，容易写混） |
| `KB_EMBED_MODEL`（或 `AI_EMBED_MODEL`） | `bge-m3` | 嵌入模型名，改它要重建索引 |
| `KB_VECTOR_ENABLED` | `1` | 置 `0` 关掉语义检索（只走关键词） |

> **用宿主机上已有的 Ollama**（不想再起一个容器）：把地址改成
> `KB_EMBED_BASE_URL=http://host.docker.internal:11434` 即可 —— compose 已给 backend 配了
> `host.docker.internal` 映射。
>
> **注意**：这些键要么在 `.env` 里给（compose 会注入），要么作为容器环境变量给。
> 早期版本只有"数据库 + 代码默认值"两条来源，**外部环境变量会被忽略** —— 现在
> `SettingsService.effective()` 的顺序是 **数据库 > 外部配置 > 代码默认值**，
> 上面那个 `/api/settings/effective` 接口就是用来一眼确认这件事的。

---

## 数据在哪、怎么备份与恢复

数据落在两个**具名卷**里，`docker compose down` 不会删：

| 卷 | 装什么 |
| --- | --- |
| `deploy_mysql-data` | 全部业务数据：笔记、资料元数据、知识库、图谱、界面设置 |
| `deploy_uploads` | 上传的资料原文（PDF/Word…） |

```bash
# 备份数据库
docker compose exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' > backup.sql

# 恢复
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < backup.sql

# 备份上传的资料原文（卷 → 本地目录）
docker compose cp backend:/app/backend/uploads ./uploads-backup

# 危险操作：连数据一起删（会清空！）
docker compose down -v
```

> **从"宿主机直接跑 jar"的旧部署迁过来**：旧库在宿主机的 3307 上，新栈的库在卷里，两者互不相通。
> 先 `mysqldump` 旧库，再按上面的命令灌进 compose 的库；`backend/uploads/` 用 `docker compose cp` 反向拷进去。

---

## 常用运维命令

```bash
docker compose ps                    # 看三个容器的状态与健康（healthy 才是真起来了）
docker compose logs -f backend       # 跟后端日志（排错主要看这个）
docker compose logs -f frontend
docker compose restart backend       # 只重启后端
docker compose up -d --build         # 更新：拉新代码后重建镜像并滚动重启
docker compose down                  # 停掉（保留数据）
```

`depends_on` + healthcheck 已配好启动顺序：**MySQL 能登 → 后端健康 → 前端起**。
所以 `up -d` 之后不必手动等，`docker compose ps` 三个都 healthy 就是好了。

---

## 更新

```bash
cd <仓库>
git pull
cd deploy
docker compose up -d --build
```

**表结构变更注意**：`schema.sql` 全是 `CREATE TABLE IF NOT EXISTS`，项目也**没有** Flyway/Liquibase。
含义是「新增表」会自动建，但「给已有表加列/加索引」**不会自动生效**，后端查询会报
`Unknown column`。遇到这种更新，先备份再手工执行一次 `ALTER`：

```bash
docker compose exec mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' <<'SQL'
ALTER TABLE note ADD COLUMN your_new_column VARCHAR(255) NULL;
SQL
```

---

## 两个真实的限制（不是配置问题，是容器网络决定的）

1. **本地 Ollama 做向量嵌入时，地址不能是 `localhost`**
   默认嵌入地址是 `http://localhost:11434`（宿主机视角）。在容器里 `localhost` 指的是**容器自己**，
   所以语义检索的"嵌入"这一步会连不上。栈里已给 backend 加了 `host.docker.internal` 映射，
   把「设置 → 检索」里的嵌入地址改成 `http://host.docker.internal:11434` 即可。
   *不影响*：不配也能用 —— 检索默认是 MySQL 全量扫描，AI 走的是云端 DeepSeek API。

2. **端口冲突时不要硬上**
   这台机器如果已经有容器占了名字 `learn-hub-mysql` 或端口 3307（例如之前用
   `docker run --name learn-hub-mysql ...` 起的旧库），`docker compose up` 会直接报冲突。
   二选一：把旧的 `docker rm -f learn-hub-mysql` 之后迁数据过来，或者改 `.env` 里的端口与
   compose 里的 `container_name`。

---

## 想用 Milvus（可选）

大规模向量（十万块以上）才需要，默认的 MySQL 全扫更省事。要用的话：

```bash
docker compose -f docker-compose.yml -f docker-compose.milvus.yml --profile milvus up -d
```

之后在后端切一下后端类型：

```bash
curl -X POST "http://localhost:18080/api/kb/vector/backend?backend=milvus"
curl -X POST "http://localhost:18080/api/kb/vector/sync"     # 把库里现成的向量灌进去，不重新嵌入
curl "http://localhost:18080/api/kb/vector/status"           # 看两个后端的块数是否一致
```
