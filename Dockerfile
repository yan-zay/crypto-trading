# 多阶段构建：固定 Maven Wrapper + Java 17 构建阶段
FROM eclipse-temurin:17-jdk-alpine@sha256:c51823b135b92507d1b67183c257b08167e57fe141c387ee7af94eddf9baa994 AS builder

WORKDIR /app

# 先复制 Wrapper 和 pom.xml，利用 Docker 缓存并锁定 Maven 3.9.9
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# 复制源代码并构建
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre-alpine@sha256:90b7615cb81e3a75f69124fb480e48981c7d56dbc9f32c614d789d3a1c3e32fe

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /app/target/crypto-trading.jar app.jar

# 固定基础镜像可能晚于 Alpine 安全仓库，构建时强制取得已修复的 OpenSSL 下限。
RUN apk add --no-cache --upgrade 'libcrypto3>=3.5.8-r0' 'libssl3>=3.5.8-r0'

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /app/data/spool /app/data/exports /app/data/market-journal \
    && chown -R appuser:appgroup /app/data
USER appuser

# 镜像绝不隐式使用 dev profile
ENV SPRING_PROFILES_ACTIVE=pro
ENV BAR_SPOOL_FILE=/app/data/spool/finalized-bars.jsonl
ENV DATASET_EXPORT_DIR=/app/data/exports
ENV MARKETDATA_REALTIME_JOURNAL_ROOT=/app/data/market-journal
VOLUME ["/app/data"]

# 暴露应用端口；管理端口默认仍只绑定容器 loopback
EXPOSE 51104 51105

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:51105/actuator/health/readiness || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]
