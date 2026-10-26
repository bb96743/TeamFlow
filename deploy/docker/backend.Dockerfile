# TeamFlow 后端镜像（多阶段构建可改为在 CI 内先 mvn package，这里直接使用本地产物）
# 构建上下文：仓库根目录（deploy/docker-compose.yml 中 context: ..）
# 前置：cd teamflow-system && mvn -B -DskipTests clean package
#
# ⚠️ 本镜像定义未经实际构建验证（开发机无 Docker）。
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="teamflow-backend"
LABEL org.opencontainers.image.description="TeamFlow 后端（Spring Boot 4 + Flyway）"

WORKDIR /app

# 可执行 JAR（teamflow-server 为启动装配模块，内含全部依赖与 db/migration 脚本）
ARG JAR_FILE=teamflow-system/teamflow-server/target/teamflow-server-0.1.0-SNAPSHOT.jar
COPY ${JAR_FILE} /app/app.jar

# 文件上传与日志目录（由 compose 挂载卷持久化）
RUN mkdir -p /data/uploads /app/logs

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"
ENV TZ=Asia/Shanghai

EXPOSE 8080

# 健康检查：/api/health（alpine 的 busybox 自带 wget）
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD wget -qO- http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
