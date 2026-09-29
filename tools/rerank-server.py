#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Cross-Encoder 重排服务（learn-hub 的检索精排后端之一）。

## 为什么要有它

learn-hub 原来的"精排"是把候选（标题+摘要）丢给对话模型，让它输出一个 order 数组
（LLM listwise 重排，见 backend/.../service/RerankService.java）。那样做能用，但：

1. **慢**：本地 qwen3:8b 一次只排 20 条，60 条候选要分 3 批，CPU 上每次调用是秒级到十几秒；
2. **有结构缺陷**：listwise 有位置偏差、长提示摊薄注意力、还有 JSON 解析失败的风险；
3. **不受控**：候选超过窗口就被切掉，模型漏写编号还得补。

Cross-Encoder 是**逐对打分**：score(query, doc) 独立算，排序完全由分数决定，
不受批次与顺序影响，也没有格式风险。560M 的 bge-reranker 在 CPU 上给 60 条候选打分
通常几百毫秒 —— 比 8B 列表重排快两个数量级，而且更准（它就是为相关性训练的）。

## 接口（对齐 Jina / TEI / Cohere 的常见形状，Java 侧只认这一种）

    POST /rerank
    {"query": "...", "documents": ["...", "..."], "top_n": 8, "model": "可选，仅用于日志"}
    → {"model": "...", "results": [{"index": 0, "relevance_score": 0.98}, ...]}

    GET /health → {"status":"ok","model":"...","loaded":true,"pairs":123}

`results` **按分数降序**、**包含全部 index**（Java 侧不依赖 top_n，自己截断）。

## 启动

    # 首次会自动下载模型（用 hf-mirror，国内快）
    set HF_ENDPOINT=https://hf-mirror.com
    python tools/rerank-server.py --model BAAI/bge-reranker-v2-m3 --port 8091

模型可选：`BAAI/bge-reranker-v2-m3`（568M，多语言，质量最好，约 2.3GB）、
`BAAI/bge-reranker-base`（278M，约 1.1GB，CPU 更快，先验证链路用这个也够）。

后端侧配置：
    kb.rerank_backend = cross
    kb.rerank_url     = http://127.0.0.1:8091/rerank
"""
import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# 模型下载端点：**默认不动**（走官方 huggingface.co）。
# 注意 hf-mirror.com 目前对 `resolve/main/...` 返回 308 重定向，huggingface_hub 会报
# LocalEntryNotFoundError（实测踩过）。只有在官方站拉不动时才切镜像：
#     set HF_ENDPOINT=https://hf-mirror.com
# 若必须用镜像，建议配合 `hf download --local-dir` 或在能跟随 308 的客户端里先下好，
# 再用 --model <本地目录> 启动本脚本。
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "60")

STATE = {"model": None, "name": "", "loaded": False, "pairs": 0, "load_seconds": 0.0}
LOCK = threading.Lock()


def load_model(name):
    from sentence_transformers import CrossEncoder
    t0 = time.time()
    # max_length=512 是这类 reranker 的常规上限；候选片段本来就只给标题+摘要
    model = CrossEncoder(name, max_length=512)
    STATE["model"] = model
    STATE["name"] = name
    STATE["loaded"] = True
    STATE["load_seconds"] = round(time.time() - t0, 1)
    print(f"[rerank] 模型已加载：{name}（{STATE['load_seconds']}s）", flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 少刷屏：只留错误
        if args and str(args[0]).startswith(("4", "5")):
            sys.stderr.write("[rerank] %s\n" % (fmt % args))

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/health"):
            self._send(200, {
                "status": "ok" if STATE["loaded"] else "loading",
                "model": STATE["name"],
                "loaded": STATE["loaded"],
                "pairs": STATE["pairs"],
                "loadSeconds": STATE["load_seconds"],
            })
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if not self.path.startswith("/rerank"):
            self._send(404, {"error": "not found"})
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(n) or b"{}")
            query = req.get("query") or req.get("q") or ""
            docs = req.get("documents") or req.get("docs") or []
            if not isinstance(docs, list) or not docs:
                self._send(400, {"error": "documents 不能为空"})
                return
            if not STATE["loaded"]:
                self._send(503, {"error": "模型还在加载"})
                return
            model = STATE["model"]
            pairs = [(query, str(d)) for d in docs]
            with LOCK:
                scores = model.predict(pairs, show_progress_bar=False)
                STATE["pairs"] += len(pairs)
            out = [{"index": i, "relevance_score": float(s)} for i, s in enumerate(scores)]
            out.sort(key=lambda x: x["relevance_score"], reverse=True)
            self._send(200, {"model": STATE["name"], "results": out})
        except Exception as e:  # 任何异常都如实回报，Java 侧据此回退到 LLM 重排
            self._send(500, {"error": f"{type(e).__name__}: {e}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=os.environ.get("RERANK_MODEL", "BAAI/bge-reranker-base"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("RERANK_PORT", "8091")))
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()

    print(f"[rerank] 启动中：model={args.model} port={args.port} "
          f"HF_ENDPOINT={os.environ.get('HF_ENDPOINT') or '(官方)'}", flush=True)
    load_model(args.model)   # 先加载再开端口：避免 Java 侧探活成功但一调用就 503
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[rerank] 就绪：http://{args.host}:{args.port}/rerank", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
