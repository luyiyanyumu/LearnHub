#
# 后端镜像：两阶段构建。
#   构建阶段用 maven + JDK 21 打 fat jar；运行阶段只留 JRE，镜像小一半左右。
#
# 为什么要先 COPY pom.xml 再 COPY src：Maven 依赖下载是整条链路里最慢的一步（几分钟），
# 单独成层之后，只改 Java 代码时依赖层命中缓存，重建往往只要十几秒。
#
# 构建上下文是**仓库根目录**（不是 backend/）：因为运行时要 /app/skills（技能提示词），
# 而 skills/ 与 backend/ 是平级的两个目录。见 deploy/docker-compose.yml 的 build.context。
#
# 基础镜像地址前缀用 ARG：实测直连 Docker Hub 会超时（`auth.docker.io` 连不上），
# 需要走加速源。默认给的是能用的国内加速源，**用不着的话在 deploy/.env 里改成空值**即可
# （compose 已把它接成 build args）： DOCKER_REGISTRY=

ARG DOCKER_REGISTRY=docker.1ms.run

# ---------- 构建阶段 ----------
FROM ${DOCKER_REGISTRY}/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 依赖层：国内直连 Maven Central 很慢，用阿里云公共仓库（与作者本机 settings.xml 一致）
# 注意：这里**不用** `RUN --mount=type=cache`（那个语法要额外拉 dockerfile 前端镜像，
# 实测在连不上 Docker Hub 的机器上会让构建直接失败）。靠"pom.xml 单独成层"吃到缓存就够了。
COPY deploy/maven-settings.xml /root/.m2/settings.xml
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline

# 源码层
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

# ---------- 运行阶段 ----------
FROM ${DOCKER_REGISTRY}/eclipse-temurin:21-jre
# curl 只给 healthcheck 用（docker compose ps / depends_on 的健康判断）
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# 布局刻意与仓库一致：/app/backend（工作目录）+ /app/skills
#   —— SkillService 的自动探测顺序是 ./skills → ../skills → ../../skills，
#      在 /app/backend 下起正好命中 /app/skills，不需要额外配置
WORKDIR /app/backend
COPY --from=build /build/target/learn-hub-backend-0.0.1-SNAPSHOT.jar ./app.jar
COPY skills /app/skills

# 上传的资料原文（PDF/Word…）落在 uploads/ 下；compose 会把它挂成卷，容器重建不丢
VOLUME ["/app/backend/uploads"]

EXPOSE 18080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# 数据库地址由 compose 用 SPRING_DATASOURCE_URL 注入（容器里必须指向 mysql 服务名，
# 不能沿用 yml 里的 localhost:3307 —— 那是给"宿主机上跑 jar"用的）
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=12 \
  CMD curl -fsS http://localhost:18080/api/files?page=1\&size=1 || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
