# 网页获取与分页续读

智能体使用 `web_search` 搜索网页，使用 `web_fetch` 读取已知 URL。搜索方式保持不变；网页读取优先使用 HTTP，按需借助可选的 Scrapling 浏览器服务执行网页自身的 JavaScript。启用浏览器服务不需要新增 API key。

## 获取模式

`web_fetch` 接受 `url`、`page_id`、`offset`、`max_chars` 和 `mode`。`url` 与 `page_id` 至少提供一个；首次获取使用 URL，续读使用已返回的 `page_id`。

| 参数 | 行为 |
| --- | --- |
| `mode: "auto"` | 默认模式。先 HTTP 获取，仅在 403、429、空 HTML 或 JavaScript 页面壳时尝试 Scrapling；404 不触发动态渲染。 |
| `mode: "http"` | 只执行 HTTP 获取，不调用浏览器服务。 |
| `mode: "dynamic"` | 明确使用浏览器服务；未配置、不可用或获取失败时返回失败，不以 HTTP 结果冒充动态成功。 |
| `offset` | 缓存正文的字符偏移，默认 0；续读使用上次返回的 `next_offset`。 |
| `max_chars` | 每页目标长度，默认 8000 字符，硬上限 12000；分页保留多行公式和代码块的原子边界，因此实际页长可能超过目标值，但不超过硬上限。 |

`auto` 的动态尝试失败或侧车不可用时，保留原 HTTP 结果，并在 warning 中说明未完成动态获取。收到访问限制或页面壳的正文不代表已读取完整内容。

## 会话快照

提取后的正文只缓存在 Java 后端内存中，按会话隔离，存活 15 分钟；整个缓存最多保留 64 份网页快照、200 万字符。侧车不保存网页正文作为长期资料，也不自动写入笔记或资料库。

单个来源正文最多保留 100000 字符，超过来源上限而被截断时以 `source_truncated` 标记。若单个公式或代码块自身超过 12000 字符，读取该块会明确失败，不能通过提高 `max_chars` 绕过分页上限，也不会静默拆开结构或跳过正文。

同一个 `page_id` 始终读取同一次获取的快照。分页不会再次联网，也不会因为页面更新而替换快照；若同时传入 `url`，需与快照返回的最终 URL 一致。其他会话不能使用该 ID；后端重启、缓存到期或容量淘汰后，续读返回无效页面，需要重新用 URL 获取。重新获取会产生新的快照，不能沿用旧偏移把不同版本拼接成一篇。

| 返回字段 | 含义 |
| --- | --- |
| `ok` / `http_success` | `ok` 表示本次获取或续读产生了有效结果，不等于目标页面返回成功；`http_success` 才表示目标状态为 2xx，访问限制和错误页仍需检查 `status`。 |
| `page_id` | 当前会话内的缓存快照标识。 |
| `fetch_mode` | 实际采用的获取模式。 |
| `offset` / `next_offset` | 当前页起点与下一页偏移；`next_offset: -1` 表示已到缓存末尾。 |
| `total_chars` | 当前缓存正文的总字符数，不是对原网站全部内容的完整性证明。 |
| `has_more` | 缓存中是否还有可续读的正文。 |
| `source_truncated` | 获取或提取阶段是否已截断来源；即使 `has_more` 为 false，也可能未覆盖整个来源。 |
| `cached` | 本次是否读取已有缓存。 |
| `status` / `contentType` / `content` | 沿用原返回中的状态、内容类型与本页正文。 |
| `truncated` | 兼容字段，等于 `has_more || source_truncated`；准确判断续读应使用 `has_more` 与 `next_offset`。 |

例如，以下是工具参数的合成示例；`example.org` 路径和快照 ID 只用于说明，不代表已获取真实网页：

```json
{
  "url": "https://example.org/learnhub-synthetic/lesson",
  "mode": "auto",
  "max_chars": 8000
}
```

收到 `has_more: true` 后，在同一会话中使用响应中的真实 ID 和偏移继续读取。示例中的数值均为合成值：

```json
{
  "page_id": "synthetic-page-id",
  "offset": 7800,
  "max_chars": 8000
}
```

不要通过递增固定页长猜测偏移，公式或代码边界可能改变实际分页位置。读取最后一页时仍应检查 `source_truncated`，并在回答中说明没有覆盖的范围。

## 启用可选浏览器服务

默认 Compose 不启动浏览器服务，后端仍可执行 HTTP 获取。源码部署在 `deploy/` 中运行：

```bash
docker compose -f docker-compose.yml -f docker-compose.web-fetcher.yml up -d --build web-fetcher backend
```

发布版后端需包含该功能，侧车从当前仓库构建：

```bash
docker compose -f docker-compose.release.yml -f docker-compose.web-fetcher.yml build web-fetcher
docker compose -f docker-compose.release.yml -f docker-compose.web-fetcher.yml up -d web-fetcher backend
```

保留该实例原有 `.env`、版本、配置目录和具名卷设置；覆盖文件只追加可选服务及后端连接，不要求替换现有实例。使用发布版 Compose 的后续维护命令继续携带同一组 `-f` 参数。

覆盖文件自动向后端注入 `LEARNHUB_SCRAPLING_URL=http://web-fetcher:8082`，仅在明确叠加时等待侧车健康；默认 Compose 没有新增服务依赖。服务的 8082 端口只在 `learn-hub` 网络中可见，不映射宿主机端口。

独立运行源码 JAR 时，也可在后端进程的环境中设置 `LEARNHUB_SCRAPLING_URL`，指向后端能够访问的受信任内部服务。Compose 内部的 `web-fetcher` 服务名只供同一 Docker 网络使用，不能直接作为宿主机 JAR 的地址。默认覆盖文件不对外发布端口；独立部署服务时应只允许后端访问，不把浏览器服务直接提供给公网。

Python、Scrapling 和 Chromium 会增加镜像体积、下载与构建时间，以及运行时的 CPU、内存和进程开销。覆盖文件限制为 2 CPU、1 GiB 内存、256 个进程，提供 512 MiB `/tmp` 与 512 MiB 共享内存；临时目录和共享内存的实际占用也受容器内存限制。浏览器临时状态在容器结束后丢弃，不需要数据卷。服务实现与本地合成测试见 [web-fetcher](../tools/web-fetcher/README.md)。

## 匿名公网与运行隔离

HTTP 和动态获取均针对匿名公网内容。动态服务实际运行页面自己的 JavaScript，主文档、重定向以及脚本发出的子资源请求都经过公网地址检查，并将连接固定到已验证地址，防止重新解析后访问内网或本机服务。非 HTTP(S)、回环、私有网络、链路本地及云元数据等目标被拒绝。

工具不提供自定义脚本、headers、cookies、代理或个人 profile 参数，不复用用户登录态。Compose 中的侧车以非 root 用户运行，移除所有 Linux capabilities，禁止提权，根文件系统只读；只开放临时目录和共享内存，不挂载业务资料、应用配置、凭据或 Docker socket。网页内容仍是外部资料，不能作为指令授权智能体执行写操作；导入或修改笔记继续走现有确认流程。
