# LearnHub 命令行安装器

需要 Node.js 22.14+ 和已启动的 Docker（含 Compose 2.20+）。

```sh
npx @luyiyanyumu/learnhub@latest start
npx @luyiyanyumu/learnhub@latest update
```

首次启动后访问 http://localhost:8888。首次下载镜像需要网络；关闭终端后应用会继续运行。

`start` 保持已安装应用版本；`update` 才下载新版镜像、暂停写入、备份数据库/资料/技能、执行数据库迁移并启动。配置、版本记录和备份默认保存在 `~/.learnhub`，数据保存在 Docker 具名卷中，与 npm 缓存无关。数据库镜像和数据卷身份在安装时保存，后续应用更新不会替换它们。

```sh
learnhub doctor
learnhub status
learnhub stop
learnhub backup
learnhub adopt --from /path/to/LearnHub/deploy
```

未全局安装时，将上面的 `learnhub` 换成 `npx @luyiyanyumu/learnhub@latest`。`adopt` 只记录现有 Compose 部署，随后 `update` 才切换发布版；源目录在切换完成前需要保留。

多实例使用不同的 `--home` 与 `--project`，首次启动时选择不同的 `--web-port`、`--backend-port`、`--mysql-port`。API 密钥可在持久目录 `.env` 中填写，也可在启动后的网页设置里填写。

升级失败会保存 `pending-update.json` 和已有备份，阻止旧程序自动连到可能已经迁移的数据库。修复问题后重新执行同版本 `update`；手动恢复应选择标记为完整的备份。数据库迁移不会随镜像自动回滚；恢复旧版本需同时恢复相应数据库和文件备份。详见仓库 `docs/npx-install.md` 与 `docs/database-migrations.md`。
