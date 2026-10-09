# LearnHub 命令行安装器

安装器通过 Docker Compose 启动和更新 LearnHub，并管理持久配置、版本记录和备份。

## 快速开始

需要 Node.js 22.14+ 和已启动的 Docker Engine / Docker Desktop（含 Compose 2.20+）。

```sh
npx @luyiyanyumu/learnhub@latest start
```

首次启动后访问 <http://localhost:8888>。首次下载镜像需要网络；关闭终端后应用会继续运行。默认只监听宿主机回环地址。

已有实例执行 `start` 会继续使用已安装的应用版本。更新应用需执行：

```sh
npx @luyiyanyumu/learnhub@latest update
```

`update` 先下载目标版本镜像，再暂停写入，备份数据库、资料、技能和配置，执行数据库迁移，并等待健康检查通过。应用更新沿用已保存的数据库镜像和数据卷。

## 常用命令

| 命令 | 用途 |
| --- | --- |
| `start` | 首次安装，或启动已保存的应用版本 |
| `update` | 备份并更新到当前安装器对应的应用版本 |
| `status` | 查看应用版本、容器和未完成更新 |
| `stop` | 停止服务，保留数据 |
| `backup` | 暂停写入，备份数据库、资料、技能和配置，再恢复服务 |
| `doctor` | 检查 Docker 和 Compose |
| `adopt --from <目录>` | 登记现有 Compose 部署 |

使用 `npx @luyiyanyumu/learnhub@latest <命令>` 执行；全局安装后可使用 `learnhub <命令>`。`--help` 可查看完整选项。

## 模型与检索

API 密钥可在实例目录的 `.env` 中填写，也可在启动后的网页设置中填写。BGE-M3 和 Ollama 均非必需。

启用语义检索时，在「设置 → 模型档案与分工」新增用途为「向量嵌入」的档案，测试服务后将「向量嵌入」分工指向它，再到知识库维护入口手动重建索引。未配置时仍可使用关键词检索。更换嵌入服务或模型后需要重建向量索引。

后端在容器内运行。连接宿主机模型服务时使用 `host.docker.internal`，并确认服务允许 Docker 网络访问；连接同一 Compose 网络中的服务时使用服务名。Ollama 嵌入地址如 `http://host.docker.internal:11434`，对话地址如 `http://host.docker.internal:11434/v1`。

## 持久配置与多实例

配置、版本记录和备份默认保存在用户主目录的 `.learnhub` 中，可通过 `--home` 指定其他目录。业务数据保存在 Docker 具名卷中；npx 缓存仅保存安装器。

创建独立实例时使用不同的目录、项目名和端口。例如：

```sh
npx @luyiyanyumu/learnhub@latest start --home ./learnhub-secondary --project learnhub-secondary --web-port 8890 --backend-port 18090 --mysql-port 3310
npx @luyiyanyumu/learnhub@latest status --home ./learnhub-secondary
```

项目名和端口在首次安装时保存。后续命令继续指定同一个 `--home`；`--no-open` 可关闭启动时自动打开浏览器。

## 接管现有部署

从仓库根目录登记原 Compose 部署：

```sh
npx @luyiyanyumu/learnhub@latest adopt --from ./deploy --project learn-hub
npx @luyiyanyumu/learnhub@latest status
npx @luyiyanyumu/learnhub@latest update
```

将 `--from` 替换为原部署目录，`--project` 必须与已有 Compose 项目名一致。`adopt` 只登记实例、数据库镜像和数据卷；后续 `update` 才切换到发布版。原部署目录和配置需保留到首次切换完成。

## 更新失败与恢复

升级失败会保留 `pending-update.json` 和已有备份，阻止旧程序自动连接到可能已迁移的数据库。排除问题后，重新执行同一目标版本的 `update`；`--to <X.Y.Z>` 可指定已发布的应用版本。

独立备份失败时执行 `backup` 重试。手动恢复应选择 `backup.json` 中 `consistent=true` 的完整备份。数据库迁移不会随镜像自动回滚，恢复旧版需同时恢复对应的数据库、文件和配置备份。

完整说明见仓库的 [npx 安装与更新](https://github.com/luyiyanyumu/LearnHub/blob/master/docs/npx-install.md) 和 [数据库迁移说明](https://github.com/luyiyanyumu/LearnHub/blob/master/docs/database-migrations.md)。
