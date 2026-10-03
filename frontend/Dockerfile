#
# 前端镜像：构建静态文件 → 交给 nginx 托管，并把 /api 反代到后端。
#
# 为什么必须要有反代这一层：dist/ 是纯静态文件，浏览器的 /api 请求会打到 nginx 自己身上，
# 不反代就是整站 404（这是换机器部署最常踩的坑，见 docs/deploy.md 第 5.2 节）。
#
# 基础镜像前缀可配置：与 backend/Dockerfile 同一套理由（直连 Docker Hub 常超时），
# 用不着加速源就在 deploy/.env 里把 DOCKER_REGISTRY 设成空值。

ARG DOCKER_REGISTRY=docker.1ms.run

# ---------- 构建阶段 ----------
FROM ${DOCKER_REGISTRY}/node:20-alpine AS build
WORKDIR /app
# 用 npm ci：锁文件在库里（frontend/package-lock.json），依赖版本可复现
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- 运行阶段 ----------
FROM ${DOCKER_REGISTRY}/nginx:1.27-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
