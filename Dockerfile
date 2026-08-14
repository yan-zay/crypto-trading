# 多阶段构建：固定 Maven Wrapper + Java 17 构建阶段
FROM eclipse-temurin:17-jdk-alpine@sha256:638937c54b6d63f0973a20501973e7c433a36b1f22262bd2b25afa7be5ff8c4a AS builder

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
FROM eclipse-temurin:17-jre-alpine@sha256:02320dd4ce20e243dfb915c686089cf9315c763084fafbb12d5c9993aee18b57

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /app/target/crypto-trading.jar app.jar

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
RUN mkdir -p /app/data/spool /app/data/exports \
    && chown -R appuser:appgroup /app/data
USER appuser

# 镜像绝不隐式使用 dev profile
ENV SPRING_PROFILES_ACTIVE=pro
ENV BAR_SPOOL_FILE=/app/data/spool/finalized-bars.jsonl
ENV DATASET_EXPORT_DIR=/app/data/exports
VOLUME ["/app/data"]

# 暴露应用端口；管理端口默认仍只绑定容器 loopback
EXPOSE 51104 51105

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:51105/actuator/health/readiness || exit 1

# 启动应用
ENTRYPOINT ["java", "-jar", "app.jar"]
