# 隔离 Python 执行环境

这个内部服务负责运行智能体生成的纯计算工具。固定入口是 `def run(input)`，输入和返回值均为 JSON。Python 标准库可用；应用数据由已有工具读取后作为输入传入，修改笔记等写操作仍由 LearnHub 原有工具与确认流程执行。

后端仅连接 broker 的内部 HTTP 地址。broker 持有 Docker Engine socket，并为每次执行创建全新容器。生成代码的容器没有网络、宿主机挂载、应用凭据或 Docker socket，使用非 root 用户、只读根目录、16 MiB 临时目录、192 MiB 内存、1 核 CPU 和 32 个进程上限。容器退出、超时或失败后均强制删除；定时清理超过 60 秒的本服务遗留容器。不能将 broker 端口或 Docker socket 暴露给公网。

从仓库根目录构建、启动（也可将第一个配置换成 `docker-compose.release.yml`）：

```sh
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.code-runner.yml build code-runner code-sandbox
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.code-runner.yml up -d code-runner backend
```

`LEARNHUB_CODE_RUNNER_URL=http://code-runner:8081` 由覆盖配置传给后端。沙箱镜像由服务端 `LEARNHUB_SANDBOX_IMAGE` 固定；请求不能指定镜像、命令、挂载路径或容器参数。离线部署需预先准备 `python:3.12-alpine` 或通过 Docker build 参数 `PYTHON_IMAGE` 选择组织维护的兼容镜像。当前仅支持 Linux Docker Engine。

## HTTP 契约

- `GET /health`：Docker Engine 和固定沙箱镜像可用时返回 `{ "ok": true, "ready": true, "available": true, "runtime": "python" }`，否则 HTTP 503。
- `POST /analyze`：`{ "source": "def run(input): return input" }`。只解析和编译 AST，不执行代码；返回 `output.fingerprint`（忽略注释与排版的 SHA-256）及 `output.language`。注册查重使用这个独立分析结果。
- `POST /execute`：`{ "source": "def run(input): return sum(input)", "input": [2,3], "timeoutMs": 8000 }`。
- 执行成功：`{ "ok": true, "output": 5, "stdout": "", "stderr": "", "elapsedMs": 12, "sourceFingerprint": "..." }`。
- 执行失败：`{ "ok": false, "code": "SYNTAX_ERROR", "error": "...", "stdout": "", "stderr": "", "elapsedMs": 12 }`。编译成功且执行器能够返回时保留 `sourceFingerprint`；容器被强杀或引擎不可用时可能没有该字段。

源代码最多 32 KiB，输入/输出各最多 200 KiB，打印输出分别截断至 8 KiB，超时范围 1–15 秒，默认 8 秒。并发上限为 2，满载立即返回 HTTP 429 `RUNNER_BUSY`。请求格式错误返回 400，超限返回 413；生成代码执行结果均以 HTTP 200 的 `ok` 表达成功或失败。引擎异常只返回固定诊断，不转发 Docker 的本机路径或配置。

注册、已有工具优先补齐、回归测试、版本切换与回退由后端管理；这个服务不注册工具，也不直接访问数据库。

## 验证

```sh
python -B -m unittest discover -s tools/code-runner -p test_runner.py -v
```

真实容器检查使用合成输入，覆盖 AST 查重、禁止分析时执行、断网、只读、用户权限、进程限制、临时数据隔离、硬超时、输出限制和并发限制。可在临时仅监听回环地址的 broker 上执行 `python -B tools/code-runner/smoke.py --url http://127.0.0.1:5188`；结束后删除临时 broker 容器。正式部署不映射 broker 端口。

容器参数和传输格式参考 [Docker Engine API](https://docs.docker.com/reference/api/engine/version/v1.45/)。
