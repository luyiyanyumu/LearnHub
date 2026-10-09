# LearnHub · 学习工作台

LearnHub 将笔记、速查卡、原始资料和智能体放在一个工作台中。通过关键词、向量检索、GraphRAG 和 LLM Wiki，从问题找到原文，也能沿着概念关系继续学习。

![License](https://img.shields.io/badge/license-PolyForm_NC_1.0.0-orange)

## 主要功能

| 模块 | 能做什么 |
| --- | --- |
| 笔记 | Markdown 与块编辑、目录、格式调整、表格编辑、LaTeX 公式、导入与导出 |
| 速查卡与分类 | 管理命令和 API 速记，用分类与标签组织内容 |
| 资料库 | 上传 PDF、Word、Excel、PPT 和文本，抽取正文并纳入知识检索 |
| 原文阅读 | 阅读 PDF、Word 和 Markdown，划词或选段后对照翻译 |
| 智能体 | 检索知识、阅读来源、创建与修改笔记、导入网络资料，保存会话与摘要 |
| 定向编辑 | 定位插入、删除、替换与格式调整，预览改动后确认应用 |
| GraphRAG | 探索概念及其关系，通过有来源的关系补充原文召回，并生成社区摘要 |
| LLM Wiki | 生成主题页、概念页和索引页，通过双链与来源入口组织知识 |
| 学习记录 | 查看学习活动热力图和最近更新 |
| 模型与技能 | 按任务选择模型，编辑技能提示词，支持独立嵌入模型配置 |
| MCP | 让支持 MCP 的客户端访问笔记、速查卡与资料库 |

公式抽取保留原图对照，并支持视觉模型识别与 LaTeX 修正。识别效果取决于文档质量与所选模型，可以在阅读器中核对和修改。

## 快速安装

准备 Node.js **22.14+** 和正在运行的 Docker（Docker Compose **2.20+**）。使用已发布的安装包启动：

```bash
npx @luyiyanyumu/learnhub@latest start
```

默认网页入口：[http://localhost:8888](http://localhost:8888)。

```bash
npx @luyiyanyumu/learnhub@latest status
npx @luyiyanyumu/learnhub@latest stop
npx @luyiyanyumu/learnhub@latest update
```

启动器使用已构建的版本镜像，实例配置默认保存在用户目录下的 `~/.learnhub`，数据库、上传资料和技能使用 Docker 具名卷持久化。

- `start` 启动当前已安装版本。
- `update` 获取新版，在更新流程中暂停写入、备份数据并执行数据库迁移。
- 已有源码 Compose 部署通过 `adopt` 接管，具体参数见安装文档。

完整说明：[安装、接管、备份与恢复](docs/npx-install.md) · [启动器命令](cli/README.md) · [发布流程](docs/releases.md)。

## 从源码部署

在仓库根目录执行：

```bash
cd deploy
cp .env.example .env
```

Windows PowerShell 可将复制命令替换为：

```powershell
Copy-Item .env.example .env
```

编辑 `deploy/.env`，设置自己的数据库密码，并按需调整端口、镜像来源和模型配置，然后启动：

```bash
docker compose up -d --build
```

默认访问 [http://localhost:8888](http://localhost:8888)。不配置模型也可以使用笔记、分类、资料管理和关键词检索；智能体、生成 Wiki 与翻译等功能需要相应模型。

Windows 也提供 `start-all.bat`、`stop-all.bat` 和 `update.bat` 作为源码 Compose 操作入口。

部署参数、可选 Ollama 服务和排错方法见 [部署说明](deploy/README.md)。

## 配置模型

启动后进入 **设置 → 模型档案与分工**，添加模型档案并为任务选择模型。聊天、润色、Wiki 生成、翻译等任务可以分别配置；会话也可以选择自己的聊天模型。

向量检索使用独立的**嵌入档案**：

- 支持 Ollama `/api/embed` 和兼容 `/embeddings` 的服务。
- 未配置嵌入档案时，关键词检索仍可使用。
- `bge-m3` 是可选模型，不要求使用特定嵌入服务。
- 更换嵌入服务或模型后，需要重建语义索引及相关图谱实体向量；不同嵌入空间的旧向量不会混用。
- 仅轮换同一服务、同一模型的 API 密钥无需重建索引。
- 旧环境变量配置可通过“兼容旧嵌入配置”使用；没有嵌入标识的旧索引需要重建。

后端运行在容器中时，模型地址必须能从容器访问。宿主机服务可使用 `host.docker.internal`；同一 Compose 网络中的服务使用服务名。

模型调用和联网工具的数据流向取决于配置的服务。API 密钥保存在部署配置或应用设置中，不应写入项目文档。

## 知识检索如何配合

检索页与智能体共用检索服务。在融合检索中，各通道召回候选原文片段，合并去重后使用 RRF 融合排名，再按配置重排。

| 通道 | 分工 |
| --- | --- |
| 关键词检索 | 查找名称、术语、代码及精确表达 |
| 向量检索 | 查找语义接近但措辞不同的原文 |
| GraphRAG 局部证据 | 从概念和直接关系找到相关来源，只使用能与当前原文核对的证据 |
| Wiki 导览 | 匹配 Wiki 小节，沿来源依赖回查当前原文，补充候选与阅读方向 |
| 全局社区摘要 | 为整体性问题补充主题概览，作为独立上下文，不与原文片段混用排名 |

### GraphRAG

图谱把来源中的概念、实体和关系组织起来。局部检索扩展概念的直接关系，用关系证据找回原文；图谱界面支持筛选、聚焦、缩放和来源查看。

全局社区摘要由相关概念及有来源的关系生成，适合回答“有哪些主题”“这些知识如何关联”等问题。来源变化后，相关摘要会被标记为过期，需要重新生成；检索使用有效的缓存摘要。

### LLM Wiki

Wiki 将分散的资料整理为主题页、概念页和索引页，保留来源依赖、引用片段与双链。它既是阅读入口，也参与检索导览。

来源更新后，受影响的导览会停用，页面显示待更新状态。可以重建受影响页面，或重新生成单页，并通过知识自检发现无效链接、引用问题与内容缺口。

详见 [Wiki 检索设计](docs/wiki-retrieval-2026-10-07.md) 和 [GraphRAG 改进说明](docs/graphrag-improvements-2026-10-07.md)。

## 开发环境

| 组件 | 要求 |
| --- | --- |
| 前端 | Node.js 22.14+、Vue 3、Vite、Element Plus、Tiptap |
| 后端 | JDK 21、Maven 3.9+、Spring Boot、MyBatis-Plus |
| 数据库 | MySQL 8，数据库结构由 Flyway 管理 |
| 部署 | Docker 与 Docker Compose 2.20+ |

先按源码部署步骤准备 `deploy/.env`，启动数据库：

```bash
cd deploy
docker compose up -d mysql
```

在另一个 PowerShell 终端，从仓库根目录启动后端：

```powershell
cd backend
$env:SPRING_DATASOURCE_PASSWORD = '替换为 deploy/.env 中设置的数据库密码'
mvn -DskipTests package
java -jar target/learn-hub-backend-0.0.1-SNAPSHOT.jar
```

默认数据库地址为 `localhost:3307/learn_hub`。如果修改了数据库端口、库名或用户，还需同步设置 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`。

另开终端启动前端：

```bash
cd frontend
npm ci
npm run dev
```

| 入口 | 默认端口 |
| --- | --- |
| Compose / npx 网页 | 8888 |
| Vite 开发页面 | 5174 |
| Vite 构建预览 | 4174 |
| 后端 API | 18080 |
| MySQL 宿主机映射 | 3307 |

开发页面默认将 `/api` 代理到 `http://localhost:18080`。如果修改后端端口，同步调整 `frontend/vite.config.js`。

### 构建与验证

在仓库根目录执行：

```bash
mvn -B -f backend/pom.xml verify
npm --prefix frontend ci
npm --prefix frontend run build
npm --prefix cli test
```

前端工具函数测试见 [前端说明](frontend/README.md)。数据库迁移集成测试需要额外启用 Testcontainers，见 [数据库迁移说明](docs/database-migrations.md)。

## 数据与更新

- Docker 具名卷保存数据库和上传资料；发布版还持久化技能。
- 普通停止与重建容器保留数据。删除数据卷会清除其中的数据。
- 数据库由 Flyway 在启动时迁移，无需手动重复导入 `schema.sql` 或 `data.sql`。
- 更新前备份数据库、上传资料、技能和实例配置。备份与恢复应使用同一部署方式对应的流程。
- 修改来源内容后，按需重建语义索引、图谱与受影响的 Wiki；生成内容保留来源核对入口。

具体操作见 [部署维护](deploy/README.md)、[安装器备份与恢复](docs/npx-install.md) 和 [数据库迁移](docs/database-migrations.md)。

## 项目结构

```text
backend/       Spring Boot API、智能体、检索与数据库迁移
frontend/      Vue 页面、编辑器与资料阅读器
deploy/        Compose 配置、反向代理与部署入口
cli/           npx 安装、更新与备份启动器
mcp/           MCP 服务与客户端接入说明
skills/        可编辑的技能提示词
docs/          功能、架构与维护文档
tools/         开发与维护工具
```

## 更多文档

- [原文阅读与翻译](docs/reader.md)
- [笔记定向编辑](docs/note-edit-tool.md)
- [智能体会话与记忆](docs/agent-memory-design.md)
- [学习活动记录](docs/learning-activity.md)
- [PDF 正文排版](docs/pdf-layout-design.md)
- [技能目录与提示词](skills/README.md)
- [MCP 接入](mcp/README.md)
- [前端开发](frontend/README.md)

## 许可证

使用 [PolyForm Noncommercial 1.0.0](LICENSE) 许可证。非商业使用与分发应遵守许可证条款；商业使用需另行获得授权。

© 2026 dyh
