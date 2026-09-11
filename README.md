# LearnHub · IT 学习工作台 V 1.0

个人知识工作台 —— 管理 Markdown 学习笔记、命令/API 速查卡，支持分类、标签、关键词搜索，并内置 **DeepSeek AI 智能体**（笔记润色 / 代码问答 / 快速建卡）。

![License](https://img.shields.io/badge/license-PolyForm_NC_1.0.0-orange)




## 功能

- 📝 **笔记**：Markdown 编辑（md-editor-v3）、目录、格式条、HTML 导出（自包含单文件）、AI 润色
- 🗂 **组织**：无限层级分类树 + 多标签 + 关键词全文过滤
- ⚡ **速查卡**：命令 / API 速记，快捷搜索
- 🤖 **智能体**：侧边悬浮对话，支持 function calling（查笔记 / 建卡 / 建分类），回答可直接保存为笔记
- 🎨 **界面**：深浅双主题、「安静的高级感」设计系统、Markdown 预览排版精修 + 低饱和代码高亮

## 技术栈

| 层   | 技术                                                                   |
| --- | -------------------------------------------------------------------- |
| 前端  | Vue 3 + Vite + Element Plus（按需引入）+ Vue Router + md-editor-v3 + axios |
| 后端  | Spring Boot 3.5.16 + MyBatis-Plus 3.5.17 + Validation                |
| 数据库 | MySQL 8（Docker 容器 `learn-hub-mysql`，库 `learn_hub`）                   |
| AI  | DeepSeek API（OpenAI 兼容协议，手写客户端，无 SDK 依赖）                             |
| 环境  | JDK 21 (Temurin) + Maven 3.9+ + Node 18+                             |

## 目录结构

```
learn-hub/
├── backend/     # Spring Boot 后端 (端口 18080)
│   ├── src/main/java/org/dyh/learnhub/
│   │   ├── controller/   # REST 接口
│   │   ├── service/      # 业务逻辑（含 DeepSeekClient / AgentService）
│   │   ├── mapper/       # MyBatis-Plus 数据访问
│   │   ├── entity/       # 实体: Category/Tag/Note/QuickRef
│   │   ├── dto/ vo/      # 入参/出参对象
│   │   ├── common/       # 统一返回 Result、分页、全局异常
│   │   └── config/       # MyBatis-Plus 分页插件、启动摘要回填
│   └── src/main/resources/
│       ├── schema.sql    # 建表(幂等，启动自动执行)
│       └── data.sql      # 种子数据(INSERT IGNORE 幂等)
└── frontend/    # Vue3 前端 (dev 端口 5174, /api 代理到 18080)
```

## 快速开始

### 0. 环境要求

JDK 21、Maven 3.9+、Node 18+、Docker（含 MySQL 8 镜像）。

### 1. 数据库（首次自行安装mysql）

```bash
docker run -d --name learn-hub-mysql \
  -e MYSQL_ROOT_PASSWORD=密码自设 -e MYSQL_DATABASE=learn_hub \
  -p 3306:3306 mysql:8 \
  --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
```

已存在则 `docker start learn-hub-mysql`。

### 2. 配置 AI 密钥（使用智能体功能时必须）

在 `backend/` 下新建 `.env` 文件（**不要提交到 git**，`.gitignore` 已忽略）：

```properties
DEEPSEEK_API_KEY=sk-xxxxxxxxxxxxxxxx
```

> 没有 key 也不影响其余功能，只有 AI 智能体相关按钮不可用。

### 3. 后端

```bash
cd backend
mvn -DskipTests package
java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar
```

启动时自动建表 + 灌入种子数据。统一返回 `{code, msg, data}`。

### 4. 前端

```bash
cd frontend
npm install      # 首次
npm run dev      # http://localhost:5174（端口固定，被占用会直接报错）
```

## 主要接口

| 方法                  | 路径                     | 说明                                   |
| ------------------- | ---------------------- | ------------------------------------ |
| GET                 | /api/stats             | 工作台总览统计                              |
| GET/POST/PUT/DELETE | /api/categories[/{id}] | 分类树管理                                |
| GET/POST            | /api/tags              | 标签列表 / 新增                            |
| GET/POST/PUT/DELETE | /api/notes[/{id}]      | 笔记 CRUD（支持 categoryId/tagId/kw 过滤分页） |
| GET/POST/PUT/DELETE | /api/quick-refs[/{id}] | 速查卡 CRUD                             |
| GET                 | /api/ai/status         | AI 配置状态（不回传密钥）                       |
| POST                | /api/ai/polish         | 笔记全文润色（分块 + 超时保护）                    |
| POST                | /api/ai/chat           | 智能体对话（工具循环 ≤8 轮）                     |
| POST                | /api/ai/test           | 连通性测试                                |
| GET/PUT             | /api/settings          | AI 设置（模型/温度/思考模式等）                   |

## 常见问题

- **MySQL 连接失败**：确认容器在跑 `docker ps`，账号 root / root123456，库 learn_hub。
- **端口被占用**：后端换端口用 `SERVER__PORT=18080 java -jar ...` 指定；前端端口固定 5174（strictPort，被占会报错而不是静默换端口）。
- **AI 请求超时**：思考型模型单次响应可能 3 分钟+，前端已对齐 300s 超时，请耐心等待或换 `deepseek-flash`（默认，最快）。
- **依赖下载慢**：Maven 已配阿里云镜像。
- **沙箱/容器内启动报 `Port 50449 in use`**：WorkBuddy 沙箱注入了 `SERVER__PORT` 环境变量，显式覆盖即可。

## License

[PolyForm Noncommercial 1.0.0](LICENSE) © 2026 dyh

**个人与非商业组织可自由使用、修改、分发**（学习、研究、爱好项目、公益/教育机构等）。**任何商业用途均需另行获得作者授权**，包括但不限于：出售本软件或包含本软件的服务、收费提供、与付费产品捆绑、企业内部业务使用。商用授权请联系作者。
