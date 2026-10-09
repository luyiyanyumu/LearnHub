# learn-hub 前端

基于 Vue 3 和 Vite 的知识工作台界面，提供总览、笔记编辑、速查卡、资料库、知识库、智能体会话和代码库页面。

主要依赖包括 Element Plus、Vue Router、TipTap、Markdown 渲染组件、PDF.js 和 KaTeX。后端接口使用统一的 `/api` 前缀。

## 开发启动

使用 Node.js **22.14+**，满足当前锁文件中 Vite、PDF.js 等依赖的运行要求。在仓库根目录运行：

```powershell
cd frontend
npm ci
npm run dev
```

开发地址为 `http://localhost:5174`。完整功能需要 learn-hub 后端运行，后端启动和配置见[项目 README](../README.md)。

## 构建与预览

在 `frontend/` 目录中执行：

```powershell
npm run build
npm run preview
```

构建产物输出到 `dist/`，预览地址为 `http://localhost:4174`。`preview` 用于检查构建结果。

工具函数测试使用 Node.js 内置测试运行器。在 `frontend/` 目录中执行：

```powershell
$testFiles = Get-ChildItem src/utils -Filter *.test.js | ForEach-Object { $_.FullName }
node --test $testFiles
```

## 接口代理与部署

[vite.config.js](vite.config.js) 配置如下：

| 模式 | 前端端口 | `/api` 代理目标 |
| --- | --- | --- |
| 开发 | `5174` | `http://localhost:18080` |
| 构建预览 | `4174` | `http://localhost:18080` |

两个模式都启用了 `strictPort`，端口占用时会报错。后端地址变化时，修改开发和预览配置中各自的代理目标。

生产部署需由 Web 服务将 `/api` 转发到后端。路由使用浏览器 history 模式，Web 服务还需将前端页面路由回退到 `index.html`，以支持直接访问或刷新子页面。

## 源码结构

```text
src/
  api/          # HTTP 请求与接口封装
  components/   # 编辑器、阅读器、知识图谱等组件
  layout/       # 工作台布局
  router/       # 页面路由与加载恢复
  utils/        # Markdown、编辑操作和知识库展示工具
  views/        # 业务页面
  main.js       # 应用入口
  style.css     # 全局样式
```

页面按路由懒加载。Element Plus 组件由 Vite 插件按需引入；新增组件后，若开发时触发依赖重新预构建，可检查 `optimizeDeps.include` 是否需要补充对应样式入口。

## 常见问题

| 现象 | 检查方向 |
| --- | --- |
| 启动时报 Node.js 版本不满足要求 | 使用 Node.js 22.14+，并对照锁文件中依赖的 `engines` 要求 |
| 端口占用 | 停止占用进程，或修改 `vite.config.js` 对应端口 |
| 页面可打开但接口失败 | 检查后端运行状态和 `/api` 代理目标 |
| 部署后刷新子页面返回 404 | 配置 history 路由回退到 `index.html` |
