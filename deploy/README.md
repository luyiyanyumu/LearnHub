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

## 端口/容器名被占用时怎么并存（本机已有别的项目或旧部署）

一键启动脚本（`start-all.bat`）会从 `deploy\.env` 读端口与容器名。**同一台机器上跑多个项目时**
必须在这里错开，否则 compose 会以"容器名已被占用"或"端口已分配"直接失败 —— 实测踩过两种：

```
Conflict. The container name "/learn-hub-mysql" is already in use by container "0e5df83c..."
Bind for 127.0.0.1:8889 failed: port is already allocated     # 被另一个项目的容器占着
```

在 `deploy\.env` 里改这六行即可（数据各自独立，互不影响）：

```dotenv
MYSQL_HOST_PORT=3309
BACKEND_HOST_PORT=18082
WEB_HOST_PORT=8890
MYSQL_CONTAINER_NAME=learn-hub-mysql-deploy
BACKEND_CONTAINER_NAME=learn-hub-backend-deploy
FRONTEND_CONTAINER_NAME=learn-hub-frontend-deploy
```

改完 `start-all.bat` 打印的端口与容器名会跟着变（脚本每次从 `.env` 读，不再写死）。

> **别为了消掉冲突直接 `docker rm learn-hub-mysql`**：旧容器如果数据在**匿名卷**里
> （`docker inspect <容器> --format "{{json .Mounts}}"` 看到的是一串 64 位十六进制名字），
> 让 compose 用新卷起一个空库，你的笔记/资料在界面上就"没了"。先备份再迁移，或直接用上面的并存方案。

### 前端容器反复重启：`host not found in upstream "backend"`

nginx 写死 `proxy_pass http://backend:18080;` 时，**启动那一刻**就要解析到这个主机名，
解析不到直接 `emerg` 退出、容器重启循环（本机实测：compose 里已有 `depends_on: service_healthy`，
但单独重启前端容器时后端不在它的解析视图里，照样崩）。

`deploy/nginx.conf` 已改成**请求时解析**：

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;      # Docker 内置 DNS
location /api/ {
    set $api_upstream "http://backend:18080";
    proxy_pass $api_upstream$request_uri;    # 变量形式 → 延迟到请求时解析
}
```

好处有两个：后端暂时没起来只是该请求 502（nginx 本身健康、页面照常打开），
后端重启换了 IP 也会立刻跟上（写死 upstream 的话 nginx 会一直打到旧 IP）。

### 从本机部署迁移数据到本栈（含"向量必须重建"这个坑）

把本机那套（自己起的 MySQL + `java -jar`）搬到 compose 时，**要搬两样东西**，
并且**有一类数据是搬不过去的**：

| 搬什么 | 怎么搬 |
| --- | --- |
| 数据库（笔记/资料元数据/图谱/设置） | `mysqldump` 导出 → 导入本栈的 `learn-hub-mysql` |
| 上传的资料原文 | 把 `backend/uploads/*` 复制进本栈的卷 `learn-hub_uploads` |
| **向量（`kb_chunk.vec`）** | ❌ **搬不了，必须重建** —— 见下 |

#### 坑 1：导出时**绝不能让它经过管道/命令输出的文本层**

```powershell
# ❌ 错误：二进制 BLOB 会在这一层被当成文本解码，非法字节被替换成 U+FFFD（EF BF BD）
docker exec learn-hub-mysql sh -c 'exec mysqldump ...' | Out-File -Encoding utf8 backup.sql

# ✅ 正确：写文件用**重定向到磁盘**（或先在容器内落地再 docker cp 出来），
#          然后把文件交给 mysql 客户端导入 —— 二进制全程不经过"读出来再写回去"
cmd /c "docker exec learn-hub-mysql sh -c \"exec mysqldump -uroot -p... --single-transaction --databases learn_hub\" > backup.sql"
docker cp backup.sql learn-hub-mysql:/tmp/restore.sql
docker exec learn-hub-mysql sh -c 'mysql -uroot -p... < /tmp/restore.sql'
```

**症状**：库导进去了、笔记与文件都在，但**语义检索 0 命中**（词面正常）。
原因就是向量字节被替换成了 `EF BF BD`（UTF-8 替换字符），解码出来是 `-6.7E+28` 这种垃圾浮点，
余弦相似度全是 0.0，被 `MIN_SCORE` 过滤掉。
排查命令（向量里出现 `EFBFBD` 即已损坏）：

```bash
docker exec learn-hub-mysql mysql -uroot -p... -N -e \
  "SELECT HEX(LEFT(vec,32)) FROM learn_hub.kb_chunk LIMIT 1;"
```

#### 坑 2：向量**重建**才是正解（不要试图"修好"搬过来的向量）

向量是**由文本算出来的产物**，文本在就一定能重算，而"修补二进制"既不划算也无必要：

```bash
# 确认嵌入服务可用（容器内能访问到 Ollama）
docker compose exec backend wget -qO- http://ollama:11434/api/tags
# 全量重建（22 个来源约 2 分钟）
curl -X POST http://localhost:18080/api/kb/rebuild
curl "http://localhost:18080/api/kb/status"      # chunks 涨回、stale=false
```

**文本有没有被上面那个坑破坏？** 用这条自查（应全为 0，非 0 说明文本也坏了，得重新导出）：

```bash
docker exec learn-hub-mysql mysql -uroot -p... -N -e "
SELECT 'notes', COUNT(*) FROM learn_hub.note WHERE content LIKE CONCAT('%', CHAR(0xEFBFBD USING utf8mb4), '%')
UNION ALL SELECT 'chunks', COUNT(*) FROM learn_hub.kb_chunk WHERE chunk_text LIKE CONCAT('%', CHAR(0xEFBFBD USING utf8mb4), '%');"
```

#### 坑 3：本机那套要**先停**，否则端口/容器名冲突

本机的 MySQL（3307）与本栈的 `learn-hub-mysql` 会抢**同一个宿主机端口**，
而本机 jar 与容器后端会抢 **18080**：

```bash
docker stop <你本机的 MySQL 容器>     # 数据在它的卷里，不会丢
docker rm  <你本机的 MySQL 容器>      # 只有确认迁移成功后再做（这一步才腾出容器名）
```

> **顺序建议**：先导出 → 停本机 MySQL 与 jar → `docker compose up -d` → 导入 → 复制上传目录 → **重建索引** → 验证。

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
