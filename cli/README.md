# LearnHub 命令行

安装器通过 Docker Compose 启动和更新 LearnHub，并管理持久配置、版本记录和备份。

## 应用操作与内置智能体

应用运行后，`app list`、`app describe` 和 `app call` 分别用于发现、查看和执行业务命令。命令目录来自当前实例已注册的业务接口，包含参数位置、请求结构和返回方式。它与内置智能体共享同一份目录。

可以直接在智能体对话中提出任务，例如“查找合成示例笔记，查看它的文件夹，准备把它移动到复习目录”。智能体会查找对应的应用命令并读取必要信息。涉及写入的操作会生成确认卡片，卡片可展开查看命令和参数。已有笔记的定向修改仍提供逐项差异和正文版本检查。

从仓库根目录，在 Windows PowerShell 中依次运行下面三个只读命令。`node` 运行 JavaScript 入口，`.\` 表示相对于当前目录的路径。应用需要先启动，且包含 `/api/cli/commands` 接口。

```powershell
node .\cli\bin\learnhub.mjs app list note.
node .\cli\bin\learnhub.mjs app describe note.page
'{"query":{"kw":"合成示例","page":1,"size":5}}' | node .\cli\bin\learnhub.mjs app call note.page --input -
```

1. `app list note.` 用 `note.` 作为命令名前缀，返回匹配的命令 ID、接口及返回类型。
2. `app describe note.page` 返回指定命令的参数和请求结构。`note.page` 是分页查询笔记的命令 ID。
3. `app call note.page` 执行这次查询。单引号让 PowerShell 原样传递 JSON，`|` 把它交给右边的程序，`--input -` 表示从标准输入读取。`query` 是查询参数，`kw` 是关键词，`page` 和 `size` 分别为页码和每页条数。结果会以 JSON 输出。任一步报错时先查看该步的错误信息。

交互模式从同一目录运行以下命令。`shell` 打开 LearnHub 自己的提示符，在其中输入 `help` 查看语法，输入 `list` 发现命令，输入 `exit` 退出。

```powershell
node .\cli\bin\learnhub.mjs shell
```

脚本可用 `--input <文件>` 读取 UTF-8 JSON，或用 `--json <JSON>` 传入单个请求。请求的 `path`、`query`、`header`、`body`、`form` 和 `files` 对应命令详情中的参数位置。终端上传的 `files` 值为显式选择的本地路径。下载用 `--output <文件>` 指定新的目标文件。流式操作逐行输出 JSON 事件，失败会输出错误并以非零状态退出。

默认从安装实例的配置读取后端端口，未保存配置时连接 `http://127.0.0.1:18080`。`--home <目录>` 选择安装实例，`--base-url <URL>` 可明确指定目标地址，也可以设置 `LEARNHUB_BASE_URL`。`--timeout-ms <毫秒>` 用于覆盖调用超时。

内置智能体的应用命令经过现有审批和审计。它可以提交应用内写入、构建索引、处理资料等操作，也可生成应用内下载链接。用户电脑上的文件路径由命令行或上传界面选择。浏览器的滚动、主题、光标和未保存草稿由前端管理。内置智能体的命令目录会注明哪些操作需要确认或仅供外部 CLI 使用。

内置智能体通过 `run_app_command` 使用以下查询命令。旧工具名继续供已保存的工作流调用。

| 查询 | 应用命令 | 工作流兼容工具 |
| --- | --- | --- |
| 查找笔记 | `note.page` | `query_notes` |
| 查找资料 | `file.page` | `list_files` |
| 查看分类树 | `category.tree` | `list_categories` |
| 搜索代码库 | `code.search` | `search_code` |

需要定位资料正文、读取带版本信息的笔记、读取完整代码或核对知识库引用时，智能体使用对应的专用工具。

### 智能体命令管道

可以在内置智能体里说“查找标题或正文包含‘合成示例’的所有笔记，把查到的笔记移到‘复习’文件夹”。智能体通过 `run_app_pipeline` 串联命令。后端汇总各页查询结果，提取 ID 并填入移动命令。确认卡片列出本次选中的 ID 和目标文件夹，确认时按这批 ID 执行。

以下是工具参数示例，文件夹编号为合成数据。

```json
{
  "steps": [
    {
      "id": "notes",
      "command": "note.page",
      "paginate": true,
      "input": { "query": { "kw": "合成示例", "page": 1, "size": 20 } }
    },
    {
      "id": "move",
      "command": "folder.move-items",
      "input": {
        "body": {
          "kind": "note",
          "ids": { "$ref": "notes#/list", "$pluck": "/id" },
          "folderId": 42
        }
      }
    }
  ]
}
```

`$ref` 可引用任一前序只读步骤的结果。`notes#/list` 表示取 `notes` 步结果的 `list` 字段，`$pluck` 则从数组的每项提取 `id`。在 `note.page` 或 `file.page` 步骤设置 `paginate: true`，后端会从指定页（默认第 1 页）持续读取至末页，保留原有查询条件。`size` 设置单页条数，可设为 1 到 100。未开启自动翻页时，只返回指定的一页。

逐项循环可用于“查找前 5 篇合成示例笔记，逐篇读取详情”。以下参数将查询到的每条笔记分别交给详情命令。

```json
{
  "steps": [
    { "id": "notes", "command": "note.page", "input": { "query": { "kw": "合成示例", "size": 5 } } },
    {
      "id": "details",
      "command": "note.detail",
      "forEach": { "$ref": "notes#/list" },
      "input": { "path": { "id": { "$item": "/id" } } }
    }
  ]
}
```

`forEach` 接收前序步骤中的数组。`$item` 用 JSON Pointer 从当前项取值，空字符串代表整项。逐项命令的结果按输入顺序组成数组，后续可用 `details#` 引用。自动翻页和循环各占一个管道步骤，单步可选其中一种。

管道最多 8 步，内部最多执行 64 次只读调用，单个自动翻页步骤最多汇总 500 条。循环仅执行只读命令，管道可在最后放一条需确认的写入命令。遇到读取失败、检测到分页不一致或达到执行上限时，流程会报错并停止。批量移动的列表为空时也会停止。

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
