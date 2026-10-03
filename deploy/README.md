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
