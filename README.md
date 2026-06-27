# Crypto Trading Signal Engine

实时加密货币交易信号引擎，基于事件驱动架构聚合市场数据并生成交易信号。

## 环境要求

- **Java 17** (推荐 Eclipse Temurin)
- **Maven 3.8+**
- **MySQL 8.0** (或使用 Docker Compose)
- **Docker & Docker Compose** (可选)

## 快速开始

### 方式一：Docker Compose (推荐)

```bash
# 启动 MySQL 并初始化数据库
docker-compose up -d

# 运行应用
mvn spring-boot:run
```

### 方式二：本地 MySQL

```bash
# 1. 确保 MySQL 运行在 localhost:3306
# 2. 创建数据库
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS crypto_trading CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 3. 运行应用
mvn spring-boot:run
```

## 构建与测试

```bash
# 运行测试
mvn clean test

# 打包
mvn clean package -DskipTests

# 运行 JAR
java -jar target/crypto-trading-1.0.0.jar

# 运行冒烟测试 (需要 MySQL)
./scripts/smoke-test.sh
```

## 配置

配置文件位置：`src/main/resources/application-{profile}.yml`

| Profile | 端口 | 说明 |
|---------|------|------|
| dev     | 51102 | 开发环境，SQL 日志 |
| sit     | 51104 | 测试环境 |
| uat     | 51104 | 预发布环境 |
| pro     | 51104 | 生产环境 |

环境变量：
- `DB_USERNAME` - 数据库用户名 (默认: root)
- `DB_PASSWORD` - 数据库密码
- `COINGLASS_API_KEY` - Coinglass API 密钥

## 项目结构

```
com.tj.crypto
├── central/          # 策略引擎核心 (EventBus, DataCenter, StrategyEngine)
├── client/           # WebSocket 客户端 (Coinglass, Binance)
├── config/           # Spring 配置
├── entity/           # 数据库实体
├── mapper/           # MyBatis-Plus Mapper
├── pojo/dto/         # 数据传输对象
├── service/          # WebSocket 生命周期管理
└── enums/            # Symbol, Indicator 枚举
```

## Docker 构建

```bash
# 构建镜像
docker build -t crypto-trading .

# 运行容器
docker run -p 51104:51104 \
  -e DB_USERNAME=root \
  -e DB_PASSWORD=123456 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/crypto_trading \
  crypto-trading
```

## 开发

```bash
# 安装 Git hooks
mvn gitflow-incremental-builder:init

# 运行测试
mvn test

# 代码格式化 (如有配置)
mvn spotless:apply
```

## License

Private - Internal Use Only