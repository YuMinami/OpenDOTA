# OpenDOTA 车云通讯诊断平台

> 远程整车诊断平台: Spring Boot 后端 × React 操作台 × 车端 Agent,走 MQTT + UDS(CAN/ISO-TP / DoIP)。
>
> 当前处于 MVP **Phase 0 — 基础设施筹备**。详见 [`doc/opendota_development_plan.md`](./doc/opendota_development_plan.md)。

---

## Getting Started(新人 30 分钟跑通)

### 1. 前置依赖

| 工具 | 版本 | 说明 |
|:---|:---:|:---|
| JDK | 21 LTS | 虚拟线程 GA,不需要 `--enable-preview` |
| Maven | 3.9+ | `mvn -v` 验证 |
| Docker | 24+ | 含 Compose V2(`docker compose` 子命令) |
| mosquitto-clients | 2.x | `mosquitto_pub` 手测 MQTT(可选) |

### 2. 起本地栈

```bash
# 在仓库根目录
cd deploy
docker compose up -d

# 等所有容器 healthy(~30s)
docker compose ps
```

组件端口速查:

| 服务 | 端口 | 访问 |
|:---|:---:|:---|
| PostgreSQL | 5432 | `psql -h localhost -U opendota -d opendota`(密码 `opendota`) |
| Redis | 6379 | `redis-cli ping` |
| EMQX Dashboard | 18083 | http://localhost:18083 (admin / public) |
| EMQX MQTT | 1883 / 8883 | TCP / TLS |
| Kafka | 9092(容器内) / 29092(宿主) | Java app 用 `localhost:29092` |
| Kafka UI | 8081 | http://localhost:8081 |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3000 | http://localhost:3000 (admin / admin) |
| Jaeger UI | 16686 | http://localhost:16686 |

### 3. 初始化 Kafka topic

```bash
bash deploy/kafka-init.sh
```

应看到 `task-dispatch-queue`、`vehicle-lifecycle`、`task-dispatch-dlq` 三个 topic(幂等,重复执行无副作用)。

### 4. 起 Spring Boot + 车端模拟器

```bash
cd opendota-server
mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev
```

观察日志:

- `Started OpenDotaApplication in X.XXX seconds`
- `✅ 已启动 2 辆模拟车`(VIN `LSVWA234567890123` 在线,`LSVWB987654321098` 离线)
- DoIP 13400 端口监听

冒烟验证:

```bash
curl http://localhost:8080/api/hello
# {"code":0,"msg":"ok","data":"..."}

nc -zv localhost 13400    # DoIP 端口 listening

mosquitto_pub -h localhost -p 1883 \
  -t 'dota/v1/cmd/single/LSVWA234567890123' \
  -m '{"msgId":"demo-1","timestamp":"2026-04-20T10:00:00Z","vin":"LSVWA234567890123","act":"single_cmd","payload":{}}'
mosquitto_sub -h localhost -p 1883 \
  -t 'dota/v1/resp/single/LSVWA234567890123' -C 1
```

### 5. 常用 Maven 操作

```bash
# 全量构建
cd opendota-server && mvn clean install

# 单模块测试
mvn test -pl opendota-diag

# 单测试类
mvn -pl opendota-mqtt test -Dtest=FooTest
```

### 6. 清理

```bash
cd deploy
docker compose down      # 保留数据卷
docker compose down -v   # 连同 pg-data / redis-data 清空(⚠️ 会丢 mock 种子数据)
```

---

## 仓库结构

```
OpenDOTA/
├── CLAUDE.md                 # 项目约束(版本、模块拓扑、异步管线)
├── README.md                 # 本文件
├── .github/workflows/ci.yml  # CI Gate:build / unit / integration / conformance
├── deploy/
│   ├── docker-compose.yml    # 本地 8 组件一键栈
│   ├── kafka-init.sh         # topic 预创建(幂等)
│   ├── prometheus.yml        # Prom 抓取配置
│   └── otel-collector-config.yaml
├── doc/                      # 设计文档(真源头,早于代码)
├── sql/
│   └── init_opendota.sql     # DDL + mock seed
└── opendota-server/          # Spring Boot 多模块
    ├── opendota-common       # Envelope / DiagAction / HexUtils
    ├── opendota-mqtt         # Paho 发布/订阅
    ├── opendota-odx          # ODX 编解码(DB-driven)
    ├── opendota-diag         # REST / SSE / DiagDispatcher
    ├── opendota-task         # Task CRUD / Kafka 派发
    ├── opendota-security     # mTLS / RBAC / 审计
    ├── opendota-observability# MDC / OTEL / SLI
    ├── opendota-admin        # ODX 上传 / 车辆 CRUD
    └── opendota-app          # 启动器 + 车端模拟器(dev profile)
```

---

## 设计文档索引

| 文档 | 用途 |
|:---|:---|
| [`doc/opendota_development_plan.md`](./doc/opendota_development_plan.md) | 逐步开发计划(Phase 0-10) |
| [`doc/opendota_protocol_spec.md`](./doc/opendota_protocol_spec.md) | MQTT topic / act / payload |
| [`doc/opendota_tech_architecture.md`](./doc/opendota_tech_architecture.md) | 异步管线 / Outbox / Kafka / RLS |
| [`doc/opendota_rest_api_spec.md`](./doc/opendota_rest_api_spec.md) | REST 契约 |
| [`doc/opendota_vehicle_agent_spec.md`](./doc/opendota_vehicle_agent_spec.md) | 车端 Agent 行为 |
| [`doc/opendota_deployment_spec.md`](./doc/opendota_deployment_spec.md) | 部署拓扑 / 容量 / DR |
| [`doc/design_review.md`](./doc/design_review.md) | v1.0→v1.4 差距分析与决策 |

---

## 故障排查

| 现象 | 原因 / 处置 |
|:---|:---|
| `docker compose up` 后 `postgres` 卡在 `starting` | `docker compose logs postgres`,通常是 5432 端口已被占用 |
| Flyway 报 `V1__baseline_v1_2.sql` 签名不匹配 | 说明改过已发布迁移,走新 V 文件,勿改旧文件 |
| 模拟器未上线 | 确认 profile `dev` 激活(启动日志找 `The following 1 profile is active: "dev"`) |
| Kafka topic 创建失败 | `docker compose ps kafka` 是否 healthy;若否,等 `kafka-init.sh` 内置 60s 重试 |
| `-P dev` 与 `-Dspring-boot.run.profiles=dev` 混用 | 前者是 Maven profile,后者是 Spring profile。启动模拟器用后者 |

---

## 版本基线

- Spring Boot **3.4.2**(GA)
- Java **21 LTS**
- Jakarta EE 10
- 虚拟线程全局启用(`spring.threads.virtual.enabled: true`,架构 §1.2,勿关闭)

升级路径见 [`doc/opendota_deployment_spec.md §4`](./doc/opendota_deployment_spec.md#4-版本升级矩阵)。
