#!/bin/bash
# Smoke test script - 验证项目可以构建、测试、启动
set -e

echo "=== Smoke Test: Start ==="

# 1. Clean & Test
echo "[1/4] Running tests..."
./mvnw clean test -B
echo "PASS: All tests passed"

# 2. Package (skip tests, already passed)
echo "[2/4] Packaging application..."
./mvnw package -DskipTests -B
echo "PASS: Package successful"

# 3. Verify JAR exists
JAR_FILE="target/crypto-trading.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "FAIL: JAR not found at $JAR_FILE"
    exit 1
fi
echo "PASS: JAR exists at $JAR_FILE"

# 4. Start application and check health (optional - requires MySQL)
echo "[3/4] Starting application..."
java -jar "$JAR_FILE" --spring.profiles.active=ci &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

echo "Waiting for application startup (30s max)..."
for i in {1..30}; do
    if curl -fsS http://localhost:51105/actuator/health/readiness > /dev/null 2>&1; then
        echo "PASS: Application started and health endpoint responding"
        echo "=== Smoke Test: All Passed ==="
        exit 0
    fi
    sleep 1
done

echo "FAIL: Readiness endpoint did not become healthy"
exit 1
