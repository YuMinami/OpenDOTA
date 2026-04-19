# OpenDOTA 部署规范

> **版本**: v1.0 (对齐协议 v1.2 / 架构 v1.2)
> **日期**: 2026-04-18
> **适用范围**: 本地开发 / 测试 / 生产环境的部署拓扑、容量与版本升级矩阵

---

## 目录

1. [部署拓扑](#1-部署拓扑)
2. [本地 docker-compose](#2-本地-docker-compose)
3. [生产 Kubernetes 部署](#3-生产-kubernetes-部署)
4. [版本升级矩阵](#4-版本升级矩阵)
5. [容量规划速查](#5-容量规划速查)
6. [灾备与 DR](#6-灾备与-dr)

---

## 1. 部署拓扑

```
┌─────────────────────── 云端管理面 ──────────────────────────┐
│                                                             │
│  Ingress (mTLS, WAF)                                        │
│     │                                                       │
│     ├─► opendota-app (Spring Boot)  × N 副本 (HPA 3~20)     │
│     │      ├─► PostgreSQL 16 主从 (1 primary + 2 replica)   │
│     │      ├─► Redis 7 Cluster (3 master + 3 replica)       │
│     │      ├─► Kafka 3.8 Cluster (3 broker, KRaft)          │
│     │      └─► EMQX 5 Cluster (3 node)                      │
│     │                                                       │
│     ├─► opendota-web (Nginx) × 2 副本                       │
│     │                                                       │
│     └─► OTEL Collector → Tempo / Prometheus / Loki          │
│              │                                              │
│              └─► Grafana / AlertManager                     │
│                                                             │
└─────────────────────────────────────────────────────────────┘
                     ▲
                     │ mTLS, MQTT over TLS (8883)
                     │
┌────────────────── 车端 ──────────────────┐
│  车端 Agent × 百万辆   │   诊断仪 × 千台  │
└──────────────────────────────────────────┘
```

## 2. 本地 docker-compose

### 2.1 文件位置

`deploy/docker-compose.yml`(本规范随附,根据此清单生成)

### 2.2 组件清单

| 服务 | 镜像 | 端口 | 用途 |
|:---|:---|:---|:---|
| `postgres` | `postgres:16-alpine` | 5432 | 主数据库 + RLS |
| `redis` | `redis:7-alpine` | 6379 | Pub/Sub + 分布式锁/限流 |
| `emqx` | `emqx/emqx:5.8` | 1883/8883/18083 | MQTT Broker,内置 Dashboard |
| `kafka` | `apache/kafka:3.8.0` | 9092 | 任务管道(KRaft 模式,无 ZK) |
| `kafka-ui` | `provectuslabs/kafka-ui:latest` | 8081 | 管理台 |
| `otel-collector` | `otel/opentelemetry-collector-contrib` | 4317 | OTLP Trace 接收 |
| `prometheus` | `prom/prometheus:v2.54.0` | 9090 | 指标 |
| `grafana` | `grafana/grafana:11.2.0` | 3000 | 可视化 |
| `jaeger` | `jaegertracing/all-in-one:1.60` | 16686 | Trace 查询 |

### 2.3 推荐 docker-compose.yml 片段

```yaml
version: '3.9'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: opendota
      POSTGRES_USER: opendota
      POSTGRES_PASSWORD: opendota
    volumes:
      - pg-data:/var/lib/postgresql/data
      - ../sql:/docker-entrypoint-initdb.d:ro
    ports: ["5432:5432"]

  redis:
    image: redis:7-alpine
    command: redis-server --appendonly yes
    ports: ["6379:6379"]
    volumes: [redis-data:/data]

  emqx:
    image: emqx/emqx:5.8
    environment:
      EMQX_LISTENERS__TCP__DEFAULT__BIND: "0.0.0.0:1883"
      EMQX_DASHBOARD__DEFAULT_PASSWORD: "public"
    ports: ["1883:1883", "8883:8883", "18083:18083"]

  kafka:
    image: apache/kafka:3.8.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LOG_DIRS: /tmp/kraft-combined-logs
    ports: ["9092:9092"]

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
    ports: ["8081:8080"]
    depends_on: [kafka]

  prometheus:
    image: prom/prometheus:v2.54.0
    volumes: ["./prometheus.yml:/etc/prometheus/prometheus.yml:ro"]
    ports: ["9090:9090"]

  grafana:
    image: grafana/grafana:11.2.0
    ports: ["3000:3000"]
    depends_on: [prometheus]

  jaeger:
    image: jaegertracing/all-in-one:1.60
    ports: ["16686:16686", "4317:4317", "4318:4318"]

volumes:
  pg-data:
  redis-data:
```

### 2.4 启动后初始化

```bash
docker-compose up -d
# 等 PG 初始化完成后,Flyway 会自动执行 V1__init.sql
# (若用 Spring Boot 内置 Flyway,启动 opendota-app 时自动执行)

# 预创建 Kafka topics
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create \
  --topic task-dispatch-queue --partitions 16 --replication-factor 1
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create \
  --topic vehicle-lifecycle --partitions 8 --replication-factor 1
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --create \
  --topic task-dispatch-dlq --partitions 4 --replication-factor 1
```

## 3. 生产 Kubernetes 部署

### 3.1 Helm Chart 约定

`deploy/charts/opendota/` 下维护 Helm Chart,包含:

- `opendota-app` Deployment + HPA
- `opendota-web` Deployment + Ingress
- `postgres` Operator(推荐 CloudNativePG)
- `kafka` Operator(推荐 Strimzi)
- `emqx` Operator(官方)
- `redis` 用 Bitnami Redis Cluster Chart

### 3.2 副本与资源配额

| 服务 | 副本 | CPU 请求 | 内存请求 | CPU 上限 | 内存上限 |
|:---|:---:|:---:|:---:|:---:|:---:|
| opendota-app | 3~20 (HPA) | 2 | 4Gi | 4 | 8Gi |
| opendota-web | 2 | 0.1 | 128Mi | 0.5 | 256Mi |
| PostgreSQL primary | 1 | 4 | 16Gi | 8 | 32Gi |
| PostgreSQL replica | 2 | 4 | 16Gi | 8 | 32Gi |
| Redis master/replica | 3 / 3 | 1 | 2Gi | 2 | 4Gi |
| Kafka broker | 3 | 2 | 4Gi | 4 | 8Gi |
| EMQX | 3 | 2 | 4Gi | 4 | 8Gi |

HPA 触发:CPU > 70% 或 `dota_mqtt_publish_latency_p95 > 100ms` 持续 3 分钟。

## 4. 版本升级矩阵

| 维度 | 当前(v1.2) | 下一步(v1.3 计划) | 触发条件 |
|:---|:---|:---|:---|
| Java | 21 LTS | 25 LTS | Spring Boot 4.0 GA 且稳定 ≥ 3 个月 |
| Spring Boot | 3.4.2 | 4.0.x GA | Jakarta EE 11 生态兼容 ≥ 90% |
| Jakarta EE | 10 | 11 | 依赖 SB 4.0 GA |
| PostgreSQL | 16 | 17 | 17 的 LOGICAL REPLICATION 稳定后 |
| Kafka | 3.8(KRaft) | 3.9 / 4.0 | KRaft 生态成熟 |
| EMQX | 5.8 | 5.9 LTS | LTS 发布节奏 |

**升级必须走**:
1. dev 环境升级 → 跑完协议附录 D 的 30 条符合性测试
2. staging 灰度 10%,观察 1 周
3. 生产按租户灰度,优先非核心车厂

## 5. 容量规划速查

详见 [架构 §12 容量规划](./opendota_tech_architecture.md#12-容量规划)。核心结论:

| 指标 | 单节点承载 | 推荐副本(100 万车) |
|:---|:---:|:---:|
| MQTT 连接(EMQX) | 100 万 | 2-3 | 
| V2C QPS(Spring Boot 消费) | 2000 | 3-5 |
| Kafka task-dispatch-queue 吞吐 | 10K msg/s/broker | 3 broker |
| PG TPS(主) | 5000 | 1 主 + 2 只读 |
| Redis Pub/Sub QPS | 50K | 3 master |
| SSE 并发连接 | 2000 (Spring Boot 虚拟线程) | 10-20 |

## 6. 灾备与 DR

| 数据 | RPO | RTO | 方案 |
|:---|:---:|:---:|:---|
| `security_audit_log` | 5 分钟 | 1 小时 | WORM + S3 Object Lock + 跨区复制 |
| `task_definition` / `task_dispatch_record` | 5 分钟 | 15 分钟 | PG WAL + 流复制 |
| `diag_record` | 5 分钟 | 30 分钟 | 同上 |
| `sse_event` | 可丢 | — | 30 天 TTL,丢失只影响断线补发 |
| Kafka topic(3 日内) | 5 分钟 | 30 分钟 | 跨 AZ 复制 `min.insync.replicas=2` |
| Redis 配置 | — | — | 无状态,重建 |

每季度演练一次 DR(见协议 §15.2.1)。

---

## 附录 A:本地环境快速验证清单

开发环境 `docker-compose up` 后:

- [ ] `psql -h localhost -U opendota -d opendota` 连接成功,`\dt` 看到 20+ 张表
- [ ] `redis-cli -h localhost PING` 返回 PONG
- [ ] 浏览器打开 http://localhost:18083 登录 EMQX Dashboard(admin/public)
- [ ] 浏览器打开 http://localhost:8081 能看到 3 个 Kafka topic
- [ ] 浏览器打开 http://localhost:16686 能看到 Jaeger UI
- [ ] 浏览器打开 http://localhost:3000 登录 Grafana(admin/admin)

## 附录 B:安全加固上线前清单

- [ ] EMQX 关闭 TCP 1883,只开 TLS 8883
- [ ] mTLS 证书签发平台就绪,CA 离线保管
- [ ] EMQX ACL 的 CN/tenant/VIN 三元校验规则激活
- [ ] PG `security_audit_log` 独立 tablespace + append-only trigger 激活
- [ ] S3 Object Lock Compliance 模式 + CRR 跨区复制配置
- [ ] Redisson 配置 Redis TLS + AUTH
- [ ] Kafka SASL_SSL + ACL(每个 consumer group 独立 credential)
- [ ] Spring Boot actuator 端点仅内网可访问(或 basic auth)
