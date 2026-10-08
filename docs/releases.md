# npm 启动器与版本发布

LearnHub 的 npm 包是 Docker Compose 启动器。Java 后端、前端 nginx 和 MySQL 在 Docker 中运行，用户电脑需要 Node.js 22.14 以上与 Docker Compose 2.20 以上。`npx` 下载的是启动器；它随后拉取对应版本的前后端镜像。

`@luyiyanyumu/learnhub@0.1.1` 和对应的公开前后端镜像已发布，GitHub Actions 的 OIDC 自动发布已验证。GitHub 版本标签固定已发布的代码；后续普通代码提交不会改变现有 npm 包，准备好新的版本号并推送对应标签后才发布新版本。

## 用户安装与更新

安装与更新：

```sh
npx @luyiyanyumu/learnhub@latest start
npx @luyiyanyumu/learnhub@latest status
npx @luyiyanyumu/learnhub@latest update
```

默认实例目录是 `~/.learnhub`；`.env` 保存应用版本、数据库镜像、端口和卷名，`releases/X.Y.Z/compose.yml` 保存该版本 npm 包携带的部署模板。配置及备份保存在实例目录，不依赖 npm 缓存或执行命令时的当前目录。多个实例可以使用不同的 `--home` 和项目名、端口、卷名。

`start` 使用实例已保存的 `LEARNHUB_VERSION`。`update` 使用当前 npm 启动器的版本作为目标，更新前备份数据库、上传文件、配置及技能提示词，停止写入后迁移并重建前后端；检查通过才把实例版本记为新版本。更新可能短暂停服。镜像版本失败或数据库迁移失败时查看命令输出和备份目录；数据库迁移已提交后，回退镜像不能撤销数据库变化，恢复备份需要单独操作。

后端升级时由 Flyway 执行带版本和校验和的迁移。初次接入旧 LearnHub 数据库保留现有数据并补齐结构。示例数据只用于新数据库初始化。迁移失败会阻止后端健康检查通过，前端也不会作为健康服务启动。

## 接入现有部署

现有部署不能靠启动一个新空实例自动迁移。先确认原部署配置，再显式接入：

```sh
npx @luyiyanyumu/learnhub@latest adopt --from ./deploy
npx @luyiyanyumu/learnhub@latest update
```

`--from` 指向包含原 `docker-compose.yml` 和 `.env` 的部署目录，沿用它的 `.env`、实际数据库及上传卷和数据库镜像。原项目名默认 `learn-hub`；自定义项目名用 `--project` 指定。接入阶段记录原服务配置，升级前从原后端备份技能提示词，再复制进新版持久卷，避免旧容器内的编辑丢失。若原服务的真实挂载与配置文件不同，先核实并明确修正来源；不要用新生成的密码连接原数据库。

发布版 Compose 保留 `mysql`、`backend`、`frontend` 服务名。默认项目名 `learn-hub` 对应原部署卷 `learn-hub_mysql-data` 和 `learn-hub_uploads`。这些名称是兼容默认值；启动器接入时使用实际识别到的卷。所有对外端口默认仅绑定 `127.0.0.1`，可以在实例 `.env` 中改 `BIND_ADDRESS`。

## 持久化与版本约定

| 内容 | 保存位置或变量 | 更新行为 |
| --- | --- | --- |
| 笔记、设置与元数据 | `MYSQL_VOLUME_NAME`，默认 `learn-hub_mysql-data` | 复用原卷，Flyway 迁移 |
| 上传的资料原文和回收站 | `UPLOADS_VOLUME_NAME`，默认 `learn-hub_uploads` | 复用原卷 |
| 技能提示词 | `SKILLS_VOLUME_NAME`，默认 `learn-hub_skills-data` | 首次从镜像填充，之后整卷保留编辑 |
| 后端自定义配置 | `CONFIG_DIR`，默认实例目录中的 `config/` | 只读挂载到 `/app/backend/config` |
| Ollama 模型（可选） | `OLLAMA_VOLUME_NAME`，默认 `learn-hub_ollama-models` | 保留模型卷，应用更新不升级 Ollama |
| 应用镜像 | `LEARNHUB_IMAGE_PREFIX` 与 `LEARNHUB_VERSION` | 前后端使用相同明确版本 |
| 数据库镜像 | `MYSQL_IMAGE` | 首装默认 `mysql:8.4`；接入保留原镜像；应用更新不修改 |

`config/` 可以放 Spring Boot 的 `application.yml` 或 `application.properties`，后端通过 `SPRING_CONFIG_ADDITIONAL_LOCATION` 读取。不要在里面重新开启 `spring.sql.init` 或改变 Flyway baseline 版本。AI 密钥也可以在界面设置，数据库中的设置优先。

技能卷保留了用户编辑，因此新版镜像里的内置提示词不会自动覆盖它。新增内置技能需要维护者审核后合并。数据库镜像建议固定到已确认的完整标签或 digest；升级 MySQL 是独立运维工作，不包含在应用 `update` 中。普通 `stop` 或容器重建保留卷；不要执行 `docker compose down -v` 删除业务数据。

## 维护者首发配置

1. 确认 npm 账号拥有 `@luyiyanyumu` scope 的发布权限。如果换 scope，同时修改 `cli/package.json`、说明文档和相关命令；如果换仓库或镜像地址，同时修改启动器默认镜像前缀和发布版 Compose。
2. 先运行 GitHub Actions 的 `Validate and release LearnHub`，保持 `publish=false`、`images_only=false`。它只执行检查和构建，验证两个架构的镜像，不向 registry 写入。
3. 首次发布尚未配置 npm 认证时，在同一个发布 commit 上手动运行，设置 `images_only=true`、`publish=false`。此模式可以选择分支，按 `cli/package.json` 的明确版本推送两端镜像，不检查 npm 认证，也不发布 npm。两个开关不能同时为 true。
4. GitHub Actions 使用仓库提供的 `GITHUB_TOKEN` 向 GHCR 发布，两端包必须允许该仓库写入。GHCR 首次包默认私有；将 `learnhub-backend` 与 `learnhub-frontend` 两包都设为 Public，再用空 Docker 配置目录匿名检查两个版本镜像。任何一个镜像不能匿名读取时，不要发布 npm。参见 [GHCR 官方说明](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)。
5. 在与镜像一致的发布 commit 中，使用本机 npm 网页登录完成首次 npm 发布：进入 `cli/`，运行 `npm login`，确认拥有 scope 权限后运行 `npm publish --access public`。本机首发不加 `--provenance`，该选项需要 CI 的 OIDC 环境。也可以在仓库 Secrets 中配置有新包发布权限的 `NPM_TOKEN`，选中版本 tag 后用完整发布流程完成首发。
6. npm 包创建后，在包设置中配置 trusted publisher：GitHub 用户 `luyiyanyumu`，仓库 `LearnHub`，workflow 文件名 `release.yml`，允许 `npm publish`；再设置仓库变量 `NPM_TRUSTED_PUBLISHING=true`。完成后可以移除 `NPM_TOKEN`。该方式要求 npm 11.5.1 以上与 Node 22.14 以上，工作流安装 npm 11 并授予 `id-token: write`。[npm 官方说明](https://docs.npmjs.com/trusted-publishers/)

后续完整发布先提高 `cli/package.json` 的版本，再在该发布 commit 上创建相同版本的 tag。例如下一版：

```sh
# 先将 cli/package.json 和 cli/package-lock.json 更新为 0.1.2，
# 并提交、推送准备发布的代码，再在该提交上创建新标签：
git tag -a v0.1.2 -m "Release 0.1.2"
git push origin v0.1.2
```

推送版本 tag 触发完整发布；手动完整发布要求选中版本 tag 并设置 `publish=true`、`images_only=false`。工作流按顺序运行：版本检查 → CLI 测试与 npm 打包检查 → Compose 检查 → 后端测试 → MySQL 8.0/8.4 隔离迁移测试 → 前端测试与构建 → 两端多架构镜像 → 使用全新 Docker 配置匿名检查两个版本镜像 → npm 发布。匿名检查失败会阻断 npm 发布；把两包设为 Public 后可只重跑失败的 npm job。完整发布未配置 npm 认证时，会在推送镜像之前终止；`images_only` 模式只跳过 npm 认证和 npm job，仍执行全部构建前检查。

本机已手动发布的版本不要再次运行完整发布，也不要推送同版本 tag 触发重复 npm 发布；npm 不允许重用同一个版本。后续自动化从新的版本开始。

发布结果使用明确版本：

```text
@luyiyanyumu/learnhub@0.1.0
ghcr.io/luyiyanyumu/learnhub-backend:0.1.0
ghcr.io/luyiyanyumu/learnhub-frontend:0.1.0
```

工作流不发布 `latest` 镜像；npm 的 `latest` dist-tag 指向最近的稳定启动器版本。不要移动既有版本 tag 或重用已发布的版本；每次更新提高 `cli/package.json` 的版本，并新增数据库迁移文件，已发布迁移不得编辑。

## 本地验证

```sh
npm --prefix cli test
cd cli
npm pack --dry-run
cd ..
mvn -B -f backend/pom.xml verify
# Docker 可用时，隔离容器中的真实数据库测试：
LEARNHUB_MIGRATION_TESTS=true LEARNHUB_MIGRATION_TEST_IMAGE=mysql:8.4 mvn -B -f backend/pom.xml -Dtest=DatabaseMigrationTest test
cd frontend
npm ci
node --test src/utils/*.test.js
npm run build
```

PowerShell 的环境变量写法是 `$env:LEARNHUB_MIGRATION_TESTS='true'`，再执行 Maven 命令。发布镜像构建明确使用 `DOCKER_REGISTRY=docker.io`，无需开发机的国内加速源配置。前端构建改用 Node 22：Vite 8 需要 Node 20.19+/22.12+，当前 `pdfjs-dist` 锁定依赖还需要 Node 22.13+。[Vite 官方说明](https://v8.vite.dev/guide/)
