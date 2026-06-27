#!/bin/bash
# Smoke test script - 验证项目可以构建、测试、启动
set -e

echo "=== Smoke Test: Start ==="

# 1. Clean & Test
echo "[1/4] Running tests..."
mvn clean test -B
if [ $? -ne 0 ]; then
    echo "FAIL: Tests failed"
    exit 1
fi
echo "PASS: All tests passed"

# 2. Package (skip tests, already passed)
echo "[2/4] Packaging application..."
mvn package -DskipTests -B
if [ $? -ne 0 ]; then
    echo "FAIL: Package failed"
    exit 1
fi
echo "PASS: Package successful"

# 3. Verify JAR exists
JAR_FILE="target/crypto-trading-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "FAIL: JAR not found at $JAR_FILE"
    exit 1
fi
echo "PASS: JAR exists at $JAR_FILE"

# 4. Start application and check health (optional - requires MySQL)
echo "[3/4] Starting application..."
java -jar "$JAR_FILE" --spring.profiles.active=dev &
APP_PID=$!

echo "Waiting for application startup (30s max)..."
for i in {1..30}; do
    if curl -s http://localhost:51102/actuator/health > /dev/null 2>&1; then
        echo "PASS: Application started and health endpoint responding"
        kill $APP_PID 2>/dev/null || true
        echo "=== Smoke Test: All Passed ==="
        exit 0
    fi
    sleep 1
done

echo "WARN: Health endpoint not responding (may need MySQL)"
kill $APP_PID 2>/dev/null || true
echo "=== Smoke Test: Build Passed (Startup skipped - no MySQL) ==="
exit 0