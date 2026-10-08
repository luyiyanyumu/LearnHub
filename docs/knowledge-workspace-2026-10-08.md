# 知识库工作区重构（2026-10-08）

## 使用流程

`/knowledge` 默认进入检索。顶部概况来自当前索引、图谱和 Wiki 接口；未读到数据时显示 `—`。三个工作区分别服务于查找原文、探索概念关系、阅读主题知识页。

- **检索知识**：融合/词面切换、最近知识、独立来源筛选、原始排名、段落位置和召回通道；下载与打开为独立按钮。Wiki 命中保留生成导览说明。查询中明确标记暂时展示的上次结果。
- **探索图谱**：概念/文档层、社区/关系/依据筛选、邻域聚焦、证据侧栏和 GraphRAG 检索验证保持可用。画布与证据区独立布局，窄屏上下排列。
- **阅读 Wiki**：新增标题搜索、页面类型和生成状态筛选；区分待更新、未生成与已生成。保留单页生成、模型选择、删除、来源依赖、实际生成片段与原文跳转。
- **知识库维护**：统一收纳索引重建及进度、检索体检、图谱/文档关联重建、社区划分、规则推理、社区摘要更新、Wiki 编译/局部更新/自检、自动更新开关。关闭抽屉保留当前任务状态。

导航写入 `tab` 和 Wiki `topic` 查询参数，明确切换页面时清除旧标题定位。已有 `section` / `heading` 深链接继续精确定位；同页维护完成可以重新读取页面。删除当前页时清除失效链接并同步后续选择。

680px 以下侧栏自动显示图标，桌面折叠偏好不变；图标导航和设置/主题按钮保留可访问名称。遵循现有青绿色浅色/深色设计令牌。

## 验证记录

- `node --test src/utils/*.test.js`：294 个测试通过。新增 13 项涵盖来源筛选保留排名/身份、高亮转义、路由参数清理、Wiki 类型/状态组合筛选和计数含义；最终这些 13 项再次通过。
- `npm run build`：成功；`git diff --check`：本轮文件无空白错误。
- 实际页面：AgentRewind 融合检索返回 10 条原文片段，位置及关键词/语义/Wiki 定位通道可见。
- 最近知识按资料筛选后显示 8/20，原始排名仍为 13–20。
- 键盘 ArrowRight/Home 可切换工作区；Wiki `entity-43e538e6c5` 的 `section-1` /「机制」深链接定位成功。
- Wiki 来源依赖仍显示真实记录：6/140 个片段、4,217 字素材；原文入口为 `/files?read=6`。
- ReAct 一跳邻域正常显示 16 个概念、25 条关系，证据侧栏能独立滚动。
- 390×844 窄屏：文档宽度 390，内容区无水平溢出；回到默认视口侧栏恢复 200px。深色主题及恢复浅色正常。
- 维护区各入口和状态展示已检查；本轮没有执行模型重建、自检或真实删除。
- 最终部署地址 `http://localhost:8890/knowledge`；运行页面 HTML 与本地构建产物 SHA-256 一致，前端启动成功，后端与数据库保持健康。

## 本地部署

标准 Docker 构建遇到已关闭的镜像代理连接，改用本地 npm 构建产物叠加已有 nginx 运行镜像。用于本次更新的 Dockerfile 与专用 ignore 文件保存在 `output/Dockerfile.knowledge-ui*`，不改变默认 Dockerfile。

旧镜像保留为 `learn-hub-frontend:before-knowledge-ui-20261008`；新镜像为 `learn-hub-frontend:knowledge-ui-20261008`，标记为 `learn-hub-frontend:local` 后仅重启 frontend 服务。

## 截图

- [重构前：检索](../docs-shots/knowledge-before-search-2026-10-08.png)
- [重构后：检索](../docs-shots/knowledge-after-search-2026-10-08.png)
- [重构后：Wiki](../docs-shots/knowledge-after-wiki-2026-10-08.png)
- [重构后：图谱邻域](../docs-shots/knowledge-after-graph-2026-10-08.png)
- [窄屏](../docs-shots/knowledge-after-mobile-2026-10-08.png)
- [深色主题](../docs-shots/knowledge-after-dark-2026-10-08.png)
