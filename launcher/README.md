# LearnHub Windows 启动器

`LearnHub.exe` 将源码项目的启动、停止、重启和诊断放在同一窗口。将 EXE 放在项目根目录并双击运行；也可以在窗口内选择另一个 LearnHub 项目目录。项目应包含 `deploy/docker-compose.yml`、`backend/` 和 `frontend/`。

运行要求为 Windows 10/11 和 Microsoft Edge WebView2 Evergreen Runtime。启动器已包含 .NET 运行时，无需另装 .NET；WebView2 未安装时按启动器提示安装。EXE 不包含 LearnHub 业务服务、Docker 或项目数据。

## 日常操作

- 自动检查 Docker 引擎、当前项目容器、网页和后端接口；状态中显示端口、响应耗时、异常原因与处理建议。
- 一键启动或停止当前模式的服务，也可单独启动、停止、重启前端或后端。已在运行的健康服务不需要反复重启。
- 修改源码后在 Docker 模式中构建前后端镜像并更新对应容器；启动器不会自动拉取或改写源代码。
- 查看服务日志与启动器活动，打开网页、项目和日志目录，或导出已脱敏的状态与诊断记录。
- 在版本页手动检查官方稳定版、查看发布说明，并在源码状态允许时更新到已检查的版本。
- 单独检查启动器更新，有可用包时一键下载、校验、替换并重新打开启动器。
- 切换项目目录、运行模式和自动检测；外观默认跟随系统，也可选择浅色或深色。窗口根据当前屏幕工作区和系统缩放调整，支持高 DPI、窗口缩放及键盘操作。

启动器使用单实例窗口，可在窗口中切换项目。关闭窗口时业务服务继续运行；若有操作正在执行，则隐藏窗口并在任务结束后退出，期间再次双击 EXE 可恢复窗口。需要停服务时使用窗口中的停止操作。

## 两种运行模式

| 模式 | 服务位置 | 前置条件 |
| --- | --- | --- |
| Docker | 当前项目 Compose 中的 MySQL、后端、nginx 前端 | Docker Desktop/Engine、Compose 2.20+；先从 `deploy/.env.example` 准备 `deploy/.env` |
| 本地开发 | 本机 Java 后端、Vite 前端；数据库使用项目 Compose | Docker、JDK 21+、Node.js 22.14+；前端已安装依赖，后端 JAR 已打包 |

Docker 模式从项目配置读取服务及映射端口，默认网页为 `http://localhost:8888`。Docker 未就绪时可在启动器中启动 Docker Desktop。首次使用先构建前后端镜像，再一键启动；启动时会补齐缺失的数据库镜像。正常停止会保留容器和数据卷。

本地开发模式默认使用 `backend/target/learn-hub-backend-0.0.1-SNAPSHOT.jar` 和前端 Vite 开发服务，页面通常为 `http://localhost:5174`。代码变更后按开发流程重新打包后端 JAR，再重启后端；前端源码由 Vite 热更新。配置及手动运行步骤见[开发环境](../README.md#开发环境)。

操作只作用于所选项目的 Compose 服务，或经项目路径与进程信息确认归属的本地进程。端口由其他项目占用、进程归属无法确认时会显示冲突并拒绝停止该进程。诊断与日志会对已识别的密码、密钥等字段脱敏；导出不包含 `.env`、数据库、上传资料或笔记正文。服务自行输出的日志仍可能包含业务内容，分享前请检查导出结果。

启动器没有删除数据卷、清空资料、后台自动升级或执行任意命令的入口。npx 安装器的版本发布、备份、接管与更新流程保持独立，见[安装器说明](../cli/README.md)。

## 版本检查与源码更新

版本页分别展示应用版本、源码状态与启动器版本。应用版本读取所选项目的 `cli/package.json`；Git 标签描述与提交标识（`git describe` / commit）用于说明当前源码位置。当前启动器版本为 **1.1.0**，使用独立更新通道。

只有点击“检查更新”才会联网查询 GitHub 官方稳定发布，不在后台定时检查，也不默认自动升级。检查结果提供目标版本、标签、提交标识与官方发布说明入口。预发布版本不作为稳定升级目标。

GitHub API 返回 403/429 时，会尝试通过官方 `/releases/latest` 重定向确认稳定标签，并继续使用 `git ls-remote` 核验提交 SHA；回退时若无法读取完整发布说明，界面会提示打开官方发布页面查看。

源码更新使用本次检查确认的稳定标签及提交，执行前再次核验，并只允许当前分支快进到该提交。存在已修改或未跟踪文件、分支分叉、分离 HEAD 或无法确定当前分支时，会明确显示阻止原因；启动器不提供强制覆盖入口。先自行保存或提交改动、处理分支状态，再重新检查更新。

更新源码期间，当前业务服务继续运行。源码更新完成后，Docker 模式需再显式点击“构建并应用”，重新构建前后端镜像并应用到对应容器；本地模式需按开发流程重新打包后端，并在依赖变化时安装前端依赖，再重启相关服务。源码更新和运行服务更新是两项独立操作。

## 启动器一键更新

“检查启动器更新”只读取与当前架构匹配的官方通道清单：[Windows x64](https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-updates/launcher-update-win-x64.json) / [Windows ARM64](https://github.com/luyiyanyumu/LearnHub/releases/download/launcher-updates/launcher-update-win-arm64.json)。检查与安装不依赖项目 Git 状态；源码有本地改动也可更新启动器。检查仍由用户手动触发，不在后台自动升级。官方通道尚未发布清单或对应安装包时，会明确显示暂无可用包；本地具备更新功能不表示公共更新源已经上线。

有新版时点击“一键更新并重启”：启动器流式下载 EXE，核验清单中的文件大小、SHA-256、PE 架构与文件内置版本。通过校验后，从当前可信 EXE 复制更新助手，确认助手就绪才退出当前窗口。助手等待旧进程退出后原子替换文件并保留备份，再打开正常界面；收到新版界面启动成功的确认才视为更新完成。启动失败或没有收到确认时，会回滚并重新打开旧版，显示失败原因。

只有下载阶段可取消；校验与替换重启阶段需等待完成。更新不会停止前后端或容器，本地后台服务宿主使用缓存副本，不占用待替换的 `LearnHub.exe`。应用源码及正在运行的业务服务仍按上一节单独更新。

## 从源码构建 EXE

需要 Node.js 22.14+ 和 .NET 10 SDK。在项目根目录的 PowerShell 中运行：

```powershell
./tools/build-launcher.ps1
```

脚本先使用 `npm ci` 安装 `launcher/ui` 的锁定依赖，再构建 Vue 界面，并发布包含运行时的 Windows x64 单文件。界面作为嵌入资源打包；最终仅将 `LearnHub.exe` 复制到项目根目录。构建不会启动或重启业务服务，也不会将本机配置、凭据或数据打包进去。

依赖已经安装且锁文件未变时可用 `-SkipNpm` 跳过安装；ARM64 设备可构建对应版本：

```powershell
./tools/build-launcher.ps1 -SkipNpm
./tools/build-launcher.ps1 -Runtime win-arm64
```

中间产物保存在 `launcher/publish/<runtime>/`，正常构建会将对应架构版本复制到根目录。运行中的旧启动器应先退出；使用 `-SkipInstall` 可仅生成发布产物而不替换根目录文件。构建还生成 `LearnHub-<runtime>.exe` 和 `launcher-update-<runtime>.json`，清单含版本、架构、下载地址、大小及 SHA-256。产物与依赖目录已加入 Git 忽略规则。

发布者可用 `-ReleaseTag` 指定 EXE 所属发布标签，默认是 `launcher-v<启动器版本>`，例如本版为 `launcher-v1.1.0`。先将版本化 EXE 上传到该标签的发布资产，再将配套 JSON 上传或替换到固定 `launcher-updates` 通道标签；构建脚本不会执行发布。独立启动器发布和通道发布应标记为 GitHub prerelease，避免占用应用源码的 `/releases/latest`；启动器可用稳定版本由清单中的版本号控制。

从发布页下载 `LearnHub-win-x64.exe` 或 `LearnHub-win-arm64.exe` 后，将其重命名为 `LearnHub.exe` 再放入项目根目录；一键更新会替换这个固定文件名。

## 自动化调用与界面验证

EXE 支持无窗口命令模式；成功退出码为 `0`，失败为非 `0`。使用绝对项目路径可以避免工作目录变化：

```powershell
./LearnHub.exe --action diagnose --mode docker --project 'C:\Projects\LearnHub' --output 'C:\Temp\learnhub-status.json'
./LearnHub.exe --action start-all --mode docker --project 'C:\Projects\LearnHub'
./LearnHub.exe --action restart --service backend --mode local --project 'C:\Projects\LearnHub'
```

版本功能也提供原生命令行参数：`--check-updates` 手动联网检查稳定发布；`--update-source --version <版本> --commit <SHA>` 使用检查结果中的版本及提交更新源码。两者均可配合 `--project` 和 `--output`。源码更新沿用图形界面的工作区与快进检查，完成后仍需单独构建并应用服务。

`--check-launcher-update` 独立检查启动器通道，无需 `--project` 或 Git，可用 `--output` 保存结果：

```powershell
./LearnHub.exe --check-launcher-update --output 'C:\Temp\learnhub-launcher-update.json'
```

界面可独立运行以检查布局与交互：

```powershell
npm ci --prefix launcher/ui
npm run dev --prefix launcher/ui
```

浏览器预览和 `LearnHub.exe --demo` 均显示明确的演示标记，使用合成状态，不调用 Docker 或控制真实服务。公开截图与测试只使用合成数据。

核心、更新助手与界面桥接测试：

```powershell
dotnet run --project launcher/LearnHub.Launcher.Core.Tests
dotnet run --project launcher/LearnHub.Launcher.Updating.Tests
npm test --prefix launcher/ui
```

已有本地前端镜像时，可运行 `./launcher/tests/smoke.ps1` 验证真实 EXE 的启动、单服务停止/重启及全部停止。该检查创建独立的合成 Compose 项目，不挂载数据卷、不映射端口，结束时仅清理它自己的测试容器和网络。

使用 `-SkipInstall` 构建后，可用 `./launcher/tests/smoke.ps1 -Launcher ./launcher/publish/win-x64/LearnHub.exe` 检验新产物，保留根目录已有 EXE。

准备独立构建的较新 EXE 后，可运行 `./launcher/tests/self-update-smoke.ps1 -NewLauncher 'C:\Temp\NewBuild\LearnHub.exe'` 验证真实进程替换。测试只复制 EXE 到 `tmp` 下的合成项目，显示 Ready 后关闭该合成预览窗口，助手会自动替换、重开并验证新版界面握手与旧版备份。演示窗口与正常启动器使用独立实例锁，不操作业务服务；结束后关闭重开的合成窗口即可。

自动验证可指定 `-Launcher` 为旧版 EXE 并加 `-Unattended`：脚本在助手就绪后关闭自己启动的合成窗口，验证更新后再关闭身份核验通过的新窗口。
