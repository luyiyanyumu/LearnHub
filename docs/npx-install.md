# npx 安装与更新 LearnHub

启动器把下载镜像、启动服务、备份和更新封装成一条命令。应用运行在 Docker 中，数据库和上传资料保存在 Docker 具名卷；npx 的缓存只存启动器，不存应用数据。

下方命令从 npm 下载启动器，并从 GHCR 下载相同版本的应用镜像。开发者也可以从仓库运行 `node cli/bin/learnhub.mjs <命令>`。

## 准备与首次启动

需要 Node.js 22.14 或更新版本，以及已启动的 Docker Engine / Docker Desktop 和 Docker Compose 2.20 或更新版本。首次使用会拉取 MySQL、后端、前端镜像，耗时取决于网络与机器性能。

```bash
npx @luyiyanyumu/learnhub@latest start
```

默认网页地址为 <http://localhost:8888>，后端为 <http://localhost:18080>。默认只监听本机。数据库首次初始化时会生成随机密码，保存在实例目录的 `.env` 中。

已有实例执行 `start` 会继续使用已安装的应用版本；启动器的 `@latest` 不会让应用自动升级。关闭终端也不会关闭容器。

可以为首次安装指定目录、Compose 项目名和端口：

```bash
npx @luyiyanyumu/learnhub@latest start --home ./my-learnhub --project my-learnhub --web-port 8890 --backend-port 18090 --mysql-port 3310 --no-open
```

`--no-open` 关闭自动打开浏览器。对同一实例执行后续命令时，继续指定相同的 `--home`。每个实例使用独立的目录、项目名和端口。

## 更新

```bash
npx @luyiyanyumu/learnhub@latest update
```

默认目标是本次启动器对应的应用版本。指定已发布版本时：

```bash
npx @luyiyanyumu/learnhub@latest update --to 0.2.0
```

版本号必须与已发布的后端、前端镜像一致；上述 `0.2.0` 是示例。更新流程为：

1. 先下载新版本应用镜像。下载失败时保留正在运行的旧版本。
2. 停止前端、后端，备份数据库、上传文件、技能和实例配置。
3. 复用原有数据卷启动新应用，后端执行数据库版本迁移。
4. 等待服务健康检查通过，再记录新版本并完成更新。

应用更新期间会短暂停机，耗时随备份数据量和数据库迁移内容而变化。应用更新沿用实例已固定的 MySQL 镜像；数据库服务器本身的升级需要单独规划。

技能卷保留用户修改过的提示词。新版本新增的内置技能需要按需合并，更新不会自动覆盖或合并已有技能目录。

如果备份、迁移或健康检查失败，实例目录会保留 `pending-update.json` 和恢复备份。此时 `start` 会拒绝重新启动旧后端，避免旧程序访问已经部分迁移的数据库。排除故障后，重新执行同一目标的 `update`：

```bash
npx @luyiyanyumu/learnhub@latest update --to 0.2.0
```

更新未完成时不能直接切换另一个目标版本。此版本不自动回滚数据库，也不提供 `restore` 命令；需要恢复时按下方步骤恢复整份备份。不要单独删除 `pending-update.json` 后启动旧版。

## 手动恢复发布版备份

恢复会把数据库、资料和技能恢复到备份时间，备份之后新增或修改的内容需要从恢复前另存的快照中人工合并。下面以 **Windows PowerShell、原实例目录、默认数据库 `learn_hub`** 为例；先替换目录，数据库名称不同则修改下方 SQL。每条 Docker 命令成功后再执行下一条，任何失败都应停止处理。

1. 选择含有 `backup.json` 且 `consistent=true` 的完整备份。先执行 `stop`，把当前实例的 `.env`、`installation.json`、`pending-update.json` 和 `config/` 另存到一个恢复前快照目录。保留原备份目录。读取备份的安装记录：

   ```powershell
   $taskInstance = (Resolve-Path 'C:\Users\you\.learnhub').Path
   $taskBackup = (Resolve-Path 'C:\Users\you\.learnhub\backups\完整备份目录').Path
   $taskBefore = Join-Path $taskInstance 'before-manual-restore'
   node cli/bin/learnhub.mjs stop --home $taskInstance
   New-Item -ItemType Directory -Path $taskBefore
   $taskSavedState = Get-Content -LiteralPath (Join-Path $taskBackup 'installation.json') -Raw | ConvertFrom-Json
   if ($taskSavedState.legacy) { throw '这是源代码部署备份，请使用原 deploy 目录和 Compose 文件恢复。' }
   ```

2. 将备份的 `.env`、`installation.json` 和 `config/` 恢复到原实例目录；`config/` 应整体替换，不与现有目录混合。将备份的 `compose.yml` 复制到安装记录中 `composeFile` 指定的位置。暂时保留 `pending-update.json`。准备原版本的 Compose 参数并创建停止的后端容器：

   ```powershell
   $taskComposeFile = Join-Path $taskInstance $taskSavedState.composeFile
   $taskComposeArgs = @('--project-name', $taskSavedState.projectName, '--env-file', (Join-Path $taskInstance '.env'), '--file', $taskComposeFile)
   $taskPreviousConfig = $env:CONFIG_DIR
   $env:CONFIG_DIR = (Join-Path $taskInstance 'config').Replace('\', '/')
   docker compose @taskComposeArgs create --no-build --pull never mysql backend
   docker compose @taskComposeArgs up -d --no-deps --pull never --wait mysql
   ```

3. 后端仍保持停止。先另存当前数据库、上传资料和技能，作为恢复前的数据快照：

   ```powershell
   New-Item -ItemType Directory -Path (Join-Path $taskBefore 'uploads'), (Join-Path $taskBefore 'skills')
   docker compose @taskComposeArgs exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --events --triggers --hex-blob --no-tablespaces --set-gtid-purged=OFF "$MYSQL_DATABASE" > /tmp/learnhub-before-restore.sql'
   docker compose @taskComposeArgs cp mysql:/tmp/learnhub-before-restore.sql (Join-Path $taskBefore 'database.sql')
   docker compose @taskComposeArgs cp backend:/app/backend/uploads/. (Join-Path $taskBefore 'uploads')
   docker compose @taskComposeArgs cp backend:/app/skills/. (Join-Path $taskBefore 'skills')
   ```

4. 恢复数据库。先重建数据库以清除新版新增的表，再导入原 SQL。输入重定向在容器内执行，避免 PowerShell 文本管道改变 SQL 文件编码：

   ```powershell
   docker compose @taskComposeArgs cp (Join-Path $taskBackup 'database.sql') mysql:/tmp/learnhub-restore.sql
   docker compose @taskComposeArgs exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "DROP DATABASE IF EXISTS learn_hub; CREATE DATABASE learn_hub CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"'
   docker compose @taskComposeArgs exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE" < /tmp/learnhub-restore.sql'
   ```

5. 恢复两个数据卷。下方临时容器只挂载当前实例配置指向的卷；它清空旧内容后退出，再把备份复制到停止的后端容器：

   ```powershell
   docker compose @taskComposeArgs run --rm --no-deps --pull never --entrypoint sh backend -c 'find /app/backend/uploads /app/skills -mindepth 1 -maxdepth 1 -exec rm -rf {} +'
   docker compose @taskComposeArgs cp ((Join-Path $taskBackup 'uploads') + '/.') backend:/app/backend/uploads
   docker compose @taskComposeArgs cp ((Join-Path $taskBackup 'skills') + '/.') backend:/app/skills
   ```

6. 数据库、两个数据卷和配置全部恢复成功后，移除未完成更新标记，启动保存的原版本，检查网页、资料和模型设置：

   ```powershell
   Remove-Item -LiteralPath (Join-Path $taskInstance 'pending-update.json') -ErrorAction SilentlyContinue
   $env:CONFIG_DIR = $taskPreviousConfig
   node cli/bin/learnhub.mjs start --home $taskInstance --no-open
   ```

若备份的安装记录为 `legacy=true`，请保留并使用原 `deploy/docker-compose.yml` 和原 `.env` 的绝对路径；用原 Compose 部署恢复数据库和上传卷，把 `skills/` 恢复到原后端容器后再启动原服务。不要把源代码部署备份当作发布版模板启动，或删除原源代码目录。

## 查看、停止和备份

```bash
npx @luyiyanyumu/learnhub@latest status
npx @luyiyanyumu/learnhub@latest stop
npx @luyiyanyumu/learnhub@latest backup
```

`stop` 停止服务并保留容器数据卷；之后可执行 `start`。对运行中的实例执行 `backup`，会暂停前端、后端的写入，创建数据库、上传资料、技能与配置的一致备份，然后恢复服务；备份期间也会短暂停机。实际保存目录会显示在命令输出中。备份包含用户资料和数据库中的配置，请按原始资料的权限保管。

独立备份失败时继续执行 `backup` 重试。如果备份已完成，只是重启服务失败，重试会复用已完成的备份。备份尚未完成时，`start` 和 `update` 都会拒绝继续。

## 接管现有 Docker Compose 部署

已经使用仓库 `deploy/docker-compose.yml` 部署的实例，要先显式接管。`--from` 指向原部署目录，`--project` 必须与已有 Compose 项目名一致：

```bash
npx @luyiyanyumu/learnhub@latest adopt --from ./deploy --project learn-hub
npx @luyiyanyumu/learnhub@latest status
npx @luyiyanyumu/learnhub@latest update
```

`adopt` 检查现有数据库与上传文件卷，并固定原数据库镜像；这一步只登记已有实例，不立即换镜像或迁移数据库。原部署的 Compose 文件和 `.env` 在首次更新前仍需保留。第一次 `update` 会备份原部署并转为版本镜像，同时保留原后端中的技能内容。之后请通过启动器管理该实例，避免两套命令交叉修改部署配置。

## 持久配置

实例目录保存以下内容，默认目录为用户主目录下的 `.learnhub`：

| 内容 | 用途 |
| --- | --- |
| `.env` | 数据库密码、端口、AI 配置、镜像版本、卷名 |
| `installation.json` | 已成功安装的版本与实例记录 |
| `releases/<版本>/compose.yml` | 每个发布版本的 Compose 文件 |
| `config/` | 可选的后端外部配置 |
| `backups/` | 数据库、上传资料、技能和配置备份 |
| `pending-update.json` | 未完成更新的目标与恢复信息 |

数据库、上传资料和技能的运行数据保存在 Docker 具名卷。删除 npm / npx 缓存不会删除这些数据；删除 Docker 卷会删除对应数据。

启动器通过 `installation.json` 选择已安装的应用版本。手动修改 `.env` 的 `LEARNHUB_VERSION` 或 `LEARNHUB_IMAGE_PREFIX` 不会切换应用版本；版本切换请使用 `update`。同一实例的修改命令使用目录锁，前一个命令结束后再执行下一个。

修改 `.env` 中的 AI 环境变量后，通过 `start` 使 Compose 应用新配置。模型档案也可以继续在 LearnHub 的设置界面管理。使用宿主机 Ollama 时，嵌入地址为 `KB_EMBED_BASE_URL=http://host.docker.internal:11434`；对话模型档案使用 `http://host.docker.internal:11434/v1`。详见[现有部署说明](../deploy/README.md)。

## 发布维护

发布者先把 `cli/package.json` 的版本更新为稳定的 `X.Y.Z`，执行验证后推送对应的 `vX.Y.Z` 标签。GitHub Actions 先通过测试，再发布相同版本号的后端、前端镜像，最后发布 npm 启动器，保证用户拿到启动器时配套镜像已经可用。

npm 首发包存在后，由有包写权限且已启用 2FA 的维护者使用 npm CLI 11.15.0 或更新版本配置 GitHub Actions 的可信发布：[官方 `npm trust` 说明](https://docs.npmjs.com/cli/v11/commands/npm-trust/)。

```bash
npm trust github @luyiyanyumu/learnhub --repo=luyiyanyumu/LearnHub --file=release.yml --allow-publish --yes
```

用 `npm trust list @luyiyanyumu/learnhub` 确认仓库、工作流和发布权限配置成功后，在 GitHub 仓库的 Settings → Secrets and variables → Actions → Variables 中设置 `NPM_TRUSTED_PUBLISHING=true`，再推送版本标签以启用 OIDC 自动发布。

新建的可信发布配置必须在 2 天内完成第一次成功的 OIDC 发布，才能绑定仓库的不可变身份；超过 2 天未成功发布会过期，需删除过期配置并重新创建，再在 2 天内完成首发。创建或重建配置需要维护者本人完成 npm 2FA 确认，`--yes` 不代替 2FA：[配置到期规则](https://docs.npmjs.com/trusted-publishers/#trusted-publisher-configuration-expiry)。

镜像和 npm 包应对目标用户可读取。更换 npm 包名或镜像仓库时，同时更新包配置、发布设置和本文命令；自建镜像仓库可以在首次启动时通过 `--image-prefix` 指定。

本地验证启动器：

```bash
npm --prefix cli test
node cli/bin/learnhub.mjs --help
cd cli
npm pack --dry-run
```

自动测试使用模拟 Docker 调用，不会修改现有部署容器。

回到仓库根目录后，真实容器验证使用独立的实例目录、Compose 项目名和空闲端口。先在本机准备好 MySQL 镜像，并把应用构建成相应版本标签，再指定镜像前缀和 `--offline`：

```bash
node cli/bin/learnhub.mjs start --home ./tmp/local-instance --project learnhub-test --image-prefix learnhub-test --to 0.1.0 --web-port 18888 --backend-port 28080 --mysql-port 13307 --offline --no-open
node cli/bin/learnhub.mjs update --home ./tmp/local-instance --to 0.2.0 --offline --no-open
```

上例要求本机已有 `learnhub-test-backend:0.1.0`、`learnhub-test-frontend:0.1.0`、`learnhub-test-backend:0.2.0`、`learnhub-test-frontend:0.2.0` 以及实例所用的 MySQL 镜像。`--offline` 检查本地镜像并跳过拉取；缺少镜像时命令失败。第二条命令只作用于上例创建的独立测试实例。
