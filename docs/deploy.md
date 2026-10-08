# 换一台电脑部署 LearnHub

这份文档给的是**在一台干净机器上从零跑起来**的完整步骤，按「最少要装什么 → 怎么起 → 怎么验证 → 出问题怎么查」组织。
所有数字（端口、账号、路径）都取自仓库里的实际配置，不是示意值。

---

## 0. 先看清：到底需要装什么

> **想最省事：只装 Docker（含 Compose），其余全交给容器。**
> 见 **[deploy/README.md](../deploy/README.md)** —— `cd deploy && cp .env.example .env && docker compose up -d --build`，
> 一条命令起 MySQL + 后端 + 前端 nginx。
>
> **JDK / Maven / Node 一个都不用自己装**：它们只出现在**镜像构建过程**里
> （后端镜像的构建阶段用 `maven:3.9-eclipse-temurin-21` 编译，运行阶段只留 `eclipse-temurin:21-jre`；
> 前端镜像的构建阶段用 `node:20-alpine` 跑 `npm ci && npm run build`），
> 跑起来之后你机器上不存在这些进程，也没有环境变量要配。
> 下面这一节是**不用 Docker 跑应用**（或在开发机上直接跑）时才需要的。
> 唯一额外条件：构建镜像要能拉基础镜像（国内直连 Docker Hub 常超时，所以默认走加速源，见 `deploy/.env`）。

| 组件 | 是否必须 | 说明 |
| --- | --- | --- |
| **JDK 21** | 必须 | `backend/pom.xml` 里 `<java.version>21</java.version>`，版本低了编译不过 |
| **MySQL 8** | 必须 | 所有数据（笔记/资料/知识库/图谱）都在这里；建表与种子数据由后端启动时自动执行 |
| **Maven 3.9+** | 必须（只构建时） | 只为把后端打成 jar；也可以只要有网，用 `mvn` 现下依赖 |
| **Node 18+** | 必须（只前端） | 装依赖 + 构建/起开发服务器 |
| **DeepSeek API Key** | 可选 | 没有也能跑，只是智能体、wiki 生成、翻译这些按钮不可用 |
| Milvus | **不需要** | 语义检索默认走 MySQL 全量扫描；配了 Milvus 且连不上会自动降级（`VectorIndexService`） |
| Docker | 推荐（非必须） | 用它起 MySQL 最省事；已有 MySQL 8 可直接用 |

> 一句话：**JDK 21 + MySQL 8 + Node 18，加上一个可选的 API Key。**

---

## 1. 拉代码

```bash
git clone https://github.com/luyiyanyumu/LearnHub.git
cd LearnHub
```

仓库里**没有**这些（都在 `.gitignore` 里，属正常）：`backend/target/`（构建产物）、
`frontend/node_modules/`、`backend/uploads/`（你本地上传的资料原文）、`backend/.env`（密钥）。
所以要自己构建、自己配密钥、资料库是空的。

---

## 2. 起 MySQL（两种方式二选一）

### 2.1 用 Docker（推荐，和开发机一致）

```bash
docker run -d --name learn-hub-mysql \
  -e MYSQL_ROOT_PASSWORD=root123456 -e MYSQL_DATABASE=learn_hub \
  -p 3307:3306 mysql:8 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

**端口必须是 3307**：`application.yml` 里写死了
`jdbc:mysql://localhost:3307/learn_hub`、`root / root123456`。想用别的端口/密码，
见下文「4.3 改数据库连接」。

### 2.2 用机器上已有的 MySQL 8

只要有 `learn_hub` 这个库、账号密码对得上即可：

```sql
CREATE DATABASE learn_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

表结构和种子数据**不用手工导入** —— 后端由 Flyway 管理版本迁移，新库初始化表结构与种子，已有 LearnHub 数据库首次接入保留原数据并补齐缺列/索引。历史 `schema.sql` / `data.sql` 不再由启动器执行，后续变化应新增迁移文件。升级前先备份，见[数据库迁移说明](database-migrations.md)。

---

## 3. 配置 AI 密钥（可跳过）

在 `backend/` 下新建 `.env`：

```properties
DEEPSEEK_API_KEY=sk-你的密钥
```

后端用 `spring-dotenv` 自动读这个文件。**不要提交它**（已忽略）。
也可以在启动后到界面「设置 → 外观与 AI」里填，数据库里的配置优先级高于 yml。

---

## 4. 起后端

### 4.1 构建

```bash
cd backend
mvn -DskipTests package        # 首次会下依赖，几分钟
```

产物：`backend/target/learn-hub-backend-0.0.1-SNAPSHOT.jar`（约 55 MB，fat jar）。

### 4.2 启动

```bash
java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar
```

**必须在这个目录下启动**：技能目录（`learnhub.skills-dir` 留空时）是按 `./skills → ../skills`
自动探测的，在 `backend/` 下起正好命中仓库根的 `skills/`。

默认端口 **18080**。换端口：

```bash
java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar --server.port=18080
```

后台常驻（Linux）：

```bash
nohup java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar > backend.log 2>&1 &
```

### 4.3 改数据库连接

`backend/src/main/resources/application.yml` 里是写死的连接串，改成你的：

```yaml
spring:
  datasource:
    url: jdbc:mysql://<主机>:<端口>/learn_hub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: <你的密码>
```

改完要**重新 `mvn package`**（yml 打进 jar 里了）。也可以用命令行覆盖，不用重新打包：

```bash
java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar \
  --spring.datasource.url="jdbc:mysql://localhost:3306/learn_hub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
  --spring.datasource.password=你的密码
```

---

## 5. 起前端

### 5.1 开发模式（局域网自用最省事）

```bash
cd frontend
npm install
npm run dev
```

固定 **5174** 端口（`strictPort: true`，端口被占会直接报错而不是偷偷换端口）。
`vite.config.js` 里已把 `/api` 代理到 `http://localhost:18080`。

想让同网段其他机器访问，加 `--host`：

```bash
npm run dev -- --host
```

### 5.2 生产模式（构建静态文件 + nginx）

```bash
cd frontend
npm install
npm run build          # 产物在 frontend/dist/
```

`dist/` 是纯静态文件，需要一个 web 服务器托管，并把 `/api` 反代到后端：

```nginx
server {
    listen 80;
    server_name _;
    root /path/to/LearnHub/frontend/dist;
    index index.html;

    # 前端是 history 路由：找不到文件就回 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 接口反代到后端
    location /api/ {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 300s;          # 智能体/翻译是长请求，别用默认 60s
        client_max_body_size 60m;         # 与后端 multipart 上限一致（单文件 50MB）
    }
}
```

> 只想快速看一眼构建产物，也可以用 `npm run preview`（4174 端口，同样带 `/api` 代理）。

---

## 6. 起完怎么验证

按顺序核对，哪一步断了就知道问题在哪一层：

```bash
# 1) 数据库活着
docker ps | grep learn-hub-mysql

# 2) 后端活着（返回 {"code":200,...}）
curl http://localhost:18080/api/files?page=1&size=3

# 3) 前端能打开，且接口通（浏览器 F12 → Network 里 /api/... 是 200）
#    打开 http://<机器IP>:5174  （或 nginx 的 80）
```

再点几个功能确认：

| 检查点 | 期望 |
| --- | --- |
| 新建一条笔记 | 能保存、能再打开（说明 MySQL 通了） |
| 资料库上传一个 PDF | 能抽正文、能阅读（说明 PDF 抽取链路正常） |
| 「设置 → 外观与 AI」 | 能填 key 并测试连通（说明 AI 配置生效） |
| 知识库 → 检索 | 能搜到刚上传的资料（说明向量/检索链路正常） |

---

## 7. 常见问题

| 现象 | 原因与解法 |
| --- | --- |
| 启动报 `Access denied for user 'root'@'172.17.0.1'` | 连到了**另一个项目的 MySQL**（宿主机 3306 常被占）。确认容器映射到 3307，或按 4.3 改连接 |
| 启动报 `Communications link failure` | MySQL 没起 / 端口不对 / 容器没映射端口（`docker port learn-hub-mysql` 为空） |
| 后端起来了但前端接口 404 | 前端只托管了静态文件、没配 `/api` 反代（见 5.2 的 nginx 片段） |
| `npm run dev` 报端口被占 | 5174 被别的项目占了。`strictPort` 故意不自动换端口（静默换端口会让人打开错页面）；先腾出 5174 或改 `vite.config.js` |
| 智能体/翻译按钮不可用 | 没配 `DEEPSEEK_API_KEY`，或「设置 → 外观与 AI」里选的档案没填 key |
| 上传大 PDF 失败 | nginx `client_max_body_size` 默认 1MB，调到 60m（后端上限：单文件 50MB、请求 60MB） |
| 长回答/翻译中途 502 | nginx `proxy_read_timeout` 默认 60s，长请求会断，调到 300s |
| 想换 18080 端口 | 后端 `--server.port=`，前端改 `vite.config.js` 的 proxy target（或 nginx `proxy_pass`） |
| **Windows 一键启动脚本只认 Windows** | `start-all.bat` / `stop-all.bat` 依赖 `cmd`；Linux/macOS 用上面的命令，或照它的逻辑写一份 shell 脚本（它做的就是：`docker start` → 起 jar → 起前端 → 开浏览器） |

---

## 8. 数据怎么带走（换机器的真正重点）

**代码在 GitHub 上，数据不在。** 要带走的是三样：

| 数据 | 在哪 | 怎么搬 |
| --- | --- | --- |
| 笔记 / 资料元数据 / 知识库 / 图谱 | MySQL 容器里 | `docker exec learn-hub-mysql mysqldump -uroot -proot123456 learn_hub > learn_hub.sql`，新机器 `docker exec -i learn-hub-mysql mysql -uroot -proot123456 learn_hub < learn_hub.sql` |
| 上传的资料原文（PDF/Word…） | `backend/uploads/`（**被 git 忽略**） | 整个目录拷过去（`.derived/` 缓存可以不带，会自动重建） |
| AI 密钥与模型档案 | `backend/.env` + 数据库 `app_setting` / `model_profile` 表 | `.env` 手动拷；表跟着上面的 dump 一起走 |

> 只搬代码不搬这三样 = 一个**全新空库**，能跑，但你原来的笔记和资料都不在。

---

## 9. 可选：MCP server（让 DeepSeek Harness 直接操作知识库）

零依赖，只要后端在跑：

```bash
cd mcp
node smoke-test.mjs          # 自测，不用装任何东西
```

接进 DSH 的配置方式与 19 个工具清单见 [mcp/README.md](../mcp/README.md)。

可选 Milvus（大规模向量时再用）：

```bash
docker compose -f docker-compose.milvus.yml up -d
# 然后切后端：POST /api/kb/vector/backend?backend=milvus
# 再用 POST /api/kb/vector/sync 把 MySQL 里现成的向量灌进去（不重新嵌入）
```
