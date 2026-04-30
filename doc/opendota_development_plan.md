# OpenDOTA 开发计划

> **版本**: v1.0(对齐协议 v1.2 / 架构 v1.2 / v1.3-v1.4 审阅闭环)
> **日期**: 2026-04-19
> **状态**: 作为编码驱动基线,由 Phase 0 开始按步推进
> **读者**: 云端 Java 后端团队、车端 Rust 团队、前端 React 团队、QA、SRE
> **更新节奏**: 每完成一个 Phase 更新一次本文件,记录实际偏差与学到的经验

---

## 目录

1. [文档定位与使用约定](#1-文档定位与使用约定)
2. [里程碑全景](#2-里程碑全景)
3. [全局开发规约](#3-全局开发规约)
4. [Phase 0: 基础设施筹备](#phase-0-基础设施筹备)
5. [Phase 1: 协议骨架与 MQTT 通道](#phase-1-协议骨架与-mqtt-通道)
6. [Phase 2: 单步诊断 E2E](#phase-2-单步诊断-e2e)
7. [Phase 3: 批量诊断与多 ECU 脚本](#phase-3-批量诊断与多-ecu-脚本)
8. [Phase 4: 云端任务系统](#phase-4-云端任务系统)
9. [Phase 5: 条件任务与车端队列操控](#phase-5-条件任务与车端队列操控)
10. [Phase 6: 资源仲裁完备](#phase-6-资源仲裁完备)
11. [Phase 7: 安全、审计、可观测](#phase-7-安全审计可观测)
12. [Phase 8: OTA 与时钟信任](#phase-8-ota-与时钟信任)
13. [Phase 9: 前端 React](#phase-9-前端-react)
14. [Phase 10: 压测、DR、上线](#phase-10-压测dr上线)
15. [符合性测试映射矩阵](#15-符合性测试映射矩阵)
16. [技术债与未解问题清单](#16-技术债与未解问题清单)
17. [运维交付物清单](#17-运维交付物清单)
18. [决策记录与变更](#18-决策记录与变更)

---

## 1. 文档定位与使用约定

### 1.1 本文件的作用

- **驱动开发节奏**:每个 Phase / Step 都是独立可验证的最小工作单元,完成即 PR 合入
- **按图施工的入口**:我(Claude)和你协作时,**你只需说"按 Phase X Step Y.Z 实现"**,我会按本文件的细则去做
- **实际进度的快照**:每完成一个 Phase 更新本文件,标注实际产出与与预期的偏差

### 1.2 与其它文档的引用关系

| 文档 | 作用 | 本文件引用频率 |
|:---|:---|:---|
| [`CLAUDE.md`](../CLAUDE.md) | 项目约束(Java 版本、模块拓扑、异步管线) | 每个 Phase |
| [`doc/opendota_protocol_spec.md`](./opendota_protocol_spec.md) | MQTT 协议细节(act / topic / payload) | Phase 1-8 |
| [`doc/opendota_tech_architecture.md`](./opendota_tech_architecture.md) | 架构模式(Outbox / Kafka / RLS / SSE) | Phase 1-7 |
| [`doc/opendota_rest_api_spec.md`](./opendota_rest_api_spec.md) | REST API 契约 | Phase 2-6 |
| [`doc/opendota_vehicle_agent_spec.md`](./opendota_vehicle_agent_spec.md) | 车端行为规范 | Phase 2-8(模拟器验证用) |
| [`doc/opendota_deployment_spec.md`](./opendota_deployment_spec.md) | 部署拓扑、docker-compose | Phase 0、Phase 10 |
| [`doc/design_review.md`](./design_review.md) | v1.0→v1.4 差距分析与决策 | 所有 Phase |
| [`doc/schema/diag_payload.schema.json`](./schema/diag_payload.schema.json) | payload JSON Schema | Phase 3-4 |
| [`doc/schema/payload_hash_canonicalization.md`](./schema/payload_hash_canonicalization.md) | hash 规范化算法 | Phase 4 |

### 1.3 Step 格式约定

```
#### Step N.M: 标题

**产出**: 具体文件路径列表
**参考**: 协议 §X.Y / 架构 §Z.W / REST §A.B
**实现要点**:
- 技术细节 1
- 技术细节 2
**验收**:
- [ ] 单元测试 FooTest.bar 通过
- [ ] 集成测试场景 X 通过
- [ ] 符合性用例 TV-N-M 通过(见协议附录 D)
```

### 1.4 时间估算口径

本文件的每个 Phase 估时假设 **1 名全时 Java 后端工程师**。前端/车端/QA 的人力按各自 Phase 内标注。实际团队配比按项目管理调整。

---

## 2. 里程碑全景

### 2.1 Phase 概览

| Phase | 名称 | 估时(单人) | 前置 | 关键交付 |
|:---:|:---|:---:|:---|:---|
| 0 | 基础设施筹备 | 3-5 天 | — | docker-compose ready,Java 模拟器可跑 |
| 1 | 协议骨架与 MQTT 通道 | 1-2 周 | 0 | Envelope 全套 DTO、MqttPublisher/Subscriber、MDC |
| 2 | 单步诊断 E2E | 1 周 | 1 | `POST /diag/cmd/single` + SSE 链路打通 |
| 3 | 批量 + 多 ECU 脚本 | 1-2 周 | 2 | `batch_cmd` / `script_cmd`、ecuScope 字典序锁 |
| 4 | 云端任务系统 | 2-3 周 | 3 | task_definition CRUD、Outbox、Kafka、周期双 ack |
| 5 | 条件任务与队列操控 | 1-2 周 | 4 | `schedule_set` conditional、queue_query/delete/pause/resume |
| 6 | 资源仲裁完备 | 1-2 周 | 5 | per-ECU 锁、channel_ready、preemptPolicy、force 抢占 |
| 7 | 安全、审计、可观测 | 1-2 周 | 6 | mTLS、RBAC、audit_log、Prometheus、OTEL |
| 8 | OTA + 时钟信任 | 1-2 周 | 7 | macro_data_transfer 断点续传、time_sync |
| 9 | 前端 React | 2-3 周 | 可从 2 开始并行 | SSE Hook、诊断控制台、任务看板 |
| 10 | 压测、DR、上线 | 1-2 周 | 9 | Gatling 10 万车压测、WORM 启用、跨区复制 |

**总计**: 单人约 **14-24 周**;合理团队配比(3 后端 + 1 前端 + 1 QA + 1 SRE)可压缩到 **10-14 周**。

### 2.2 依赖关系图

```
Phase 0
  └─▶ Phase 1 (MQTT/Envelope 基础)
        └─▶ Phase 2 (单步诊断)
              ├─▶ Phase 3 (批量 + 脚本)
              │     └─▶ Phase 4 (任务系统)
              │           ├─▶ Phase 5 (条件任务 + 队列)
              │           └─▶ Phase 6 (资源仲裁)
              │                 └─▶ Phase 7 (安全 + 可观测)
              │                       └─▶ Phase 8 (OTA)
              └─▶ Phase 9 (前端,可 Phase 2 后并行)

Phase 10 由 Phase 7-9 汇合
```

### 2.3 关键里程碑

| 里程碑 | 完成时间 | 标志 |
|:---|:---|:---|
| **M1 协议打通** | Phase 2 末 | 前端点击"读 VIN" → 浏览器 SSE 收到 62F190... 翻译结果 |
| **M2 任务系统 MVP** | Phase 4 末 | 创建周期任务 → 模拟器每 5 分钟跑一次 → `task_execution_log` 增长 |
| **M3 生产级加固** | Phase 7 末 | mTLS / RBAC / audit / SLO 看板齐全,符合上线安全审计标准 |
| **M4 可上线** | Phase 10 末 | Gatling 压测 + DR 演练通过,灰度 5% 流量 |

---

## 3. 全局开发规约

### 3.1 Git 工作流(trunk-based)

```
main (protected) ← 每个 PR 由 Code Review 合入
  │
  ├─ feature/phase1-mqtt-publisher    # 一个 feature 分支 = 一个 Phase 的一个 Step
  ├─ feature/phase2-diag-channel
  └─ fix/phase4-outbox-race-condition
```

- **分支命名**: `{feature|fix|refactor|docs|chore}/phase{N}-{短描述}`
- **合入**: Squash and merge,单 PR 对应 1-3 个 commit
- **保护**: `main` 禁止直推,必须 ≥1 个 reviewer 批准 + 所有 CI 通过
- **发布**: `v1.0.0-rc.N` tag 在 Phase 10 末打

### 3.2 Commit 规范

```
feat(diag): 实现 POST /diag/cmd/single 端点

- DiagDispatcher.publish 走 Outbox,不直接发 MQTT
- 支持 channelId 关联校验
- 符合协议 §5.2 single_cmd payload 结构

Refs: Phase 2 Step 2.3
```

- **type**: `feat` / `fix` / `refactor` / `docs` / `test` / `chore` / `perf`
- **scope**: 模块名简写 `common` / `mqtt` / `diag` / `task` / `security` / `observability` / `odx` / `admin` / `simulator`
- **正文**: 用 why 说明,避免只写 what
- **Refs**: 必带,指向本文档的 Step 编号

### 3.3 PR 模板(`.github/pull_request_template.md`)

```markdown
## 关联 Phase / Step
Phase X Step Y.Z

## 变更摘要
- 做了什么
- 为什么(业务/技术原因)

## 影响面
- [ ] 协议变更(需 bump `dota/vN` 吗)
- [ ] DB schema 变更(Flyway V{n})
- [ ] 向后兼容(是否破坏已有调用方)

## 验收证据
- [ ] 单元测试 YyyyTest 通过(新增 N 个)
- [ ] 集成测试场景 X.Y 通过
- [ ] 符合性用例 TV-N-M 通过(若适用)
- [ ] 手动验证: `cd opendota-server && mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev` + `curl ...` 返回 ✅

## 文档更新
- [ ] 协议 §X.Y
- [ ] 架构 §Z.W
- [ ] REST §A.B
- [ ] 本开发计划 Step 标记完成

## 运维注意
- [ ] 新增 ENV
- [ ] 新增 Prometheus 指标
- [ ] 新增告警规则
```

### 3.4 代码审阅 Checklist

- [ ] 按约定命名(无魔法字符串,act/status 用 enum)
- [ ] MDC 五字段在所有异步边界注入(traceId/msgId/vin/operatorId/tenantId)
- [ ] PG 写操作全部在 `@Transactional` 内
- [ ] 外部 IO(Kafka/Redis/MQTT)走 Outbox 或明确容错分支
- [ ] RLS 约束不可绕过(除了 Outbox Relay 的独立 BYPASSRLS 账号)
- [ ] 新增 `act` / `dispatch_status` / error code 同步更新三份 spec
- [ ] 无注释墓碑(删除代码不留 `// was used for X`)
- [ ] 中文注释保持(架构 §11.1)

### 3.5 CI Gates(GitHub Actions)

```
.github/workflows/ci.yml
  ├─ build:          mvn clean install
  ├─ unit-tests:     mvn test
  ├─ integration:    mvn verify -Pintegration (Testcontainers)
  ├─ conformance:    mvn test -Pconformance (协议附录 D 30 用例)
  ├─ lint:           checkstyle + spotbugs
  ├─ license-check:  第三方依赖 license 扫描
  └─ sec-scan:       OWASP dependency-check
```

所有 gates 必须通过才允许合入 `main`。

### 3.6 测试金字塔

| 层级 | 占比 | 工具 | 运行时机 |
|:---|:---:|:---|:---|
| 单元测试 | 70% | JUnit 5 + Mockito + AssertJ | 每次 `mvn test` |
| 集成测试 | 20% | Spring Boot Test + Testcontainers | 每次 PR |
| 符合性测试 | 5% | 自研框架 + 协议附录 D | 每次 PR |
| 端到端测试 | 5% | Playwright(Phase 9 起) | 每日 main nightly |
| 压测 | — | Gatling | Phase 10 + 季度回归 |

---

## Phase 0: 基础设施筹备

**目标**:开发第一天前的所有工具链齐备,在 `opendota-server/` 目录执行 `mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev` 能起一个完整的本地栈并看到模拟器响应。

**前置条件**:无(这是起点)

**文档锚点**:部署 §2、架构 §8、design_review.md §9 v1.4

**估时**:3-5 天(很多已完成)

**已完成的部分**(v1.4 审阅时):
- ✅ 9 个 Maven 模块骨架 + 父 POM
- ✅ Flyway V1-V7 迁移 + R__mock_seed 隔离到 db/dev/
- ✅ `opendota-common` Envelope 核心类(DiagMessage/DiagAction/Operator/HexUtils)
- ✅ Java CAN + DoIP 模拟器骨架(`com.opendota.simulator`)
- ✅ application.yml + application-dev.yml

### Step 0.1: docker-compose 本地栈

**产出**:`deploy/docker-compose.yml`(如未提交),`deploy/prometheus.yml`

**参考**:部署 §2.2

**实现要点**:
- 组件:postgres:16-alpine / redis:7-alpine / emqx/emqx:5.8 / apache/kafka:3.8.0 / prometheus / grafana / jaeger
- PostgreSQL 挂载 `./sql:/docker-entrypoint-initdb.d:ro`,启动时执行 `init_opendota.sql`(双保险,Flyway 也会跑 V1-V7)
- Kafka KRaft 模式(无 Zookeeper)
- EMQX Dashboard 密码改为 env 变量

**验收**:
- [ ] `docker-compose up -d` 成功,所有容器 healthy
- [ ] `psql -h localhost -U opendota -d opendota -c '\dt'` 看到 20+ 张表
- [ ] `curl http://localhost:18083` EMQX Dashboard 可访问
- [ ] `curl http://localhost:8081` Kafka UI 可访问

### Step 0.2: Kafka topic 预创建脚本

**产出**:`deploy/kafka-init.sh`

**参考**:部署 §2.4

**实现要点**:
- 创建 3 个 topic:`task-dispatch-queue`(16 分区)、`vehicle-lifecycle`(8 分区)、`task-dispatch-dlq`(4 分区)
- `replication-factor=1` 本地,生产改为 3
- 脚本幂等(topic 已存在时忽略错误)

**验收**:
- [ ] 执行脚本后 `kafka-topics --list` 显示三个 topic
- [ ] `kafka-ui` UI 能看到 topic 结构

### Step 0.3: 模拟器基线验证

**产出**:无新增文件,只验证已有骨架

**参考**:本计划 §Phase 0 已完成列表

**实现要点**:
- 在 `opendota-server/` 目录执行 `mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev`
- 观察日志:启动 2 辆模拟车,其中 LSVWA... 在线,LSVWB... 保持离线
- 手动发 MQTT 消息测试:`mosquitto_pub -t 'dota/v1/cmd/single/LSVWA234567890123' -m '{...}'` 应收到模拟响应

**验收**:
- [ ] 启动日志显示 "✅ 已启动 2 辆模拟车"
- [ ] MQTT pub 后能在 `dota/v1/resp/single/LSVWA234567890123` 上订阅到响应
- [ ] DoIP 端口 13400 监听,`nc -zv localhost 13400` 成功

### Step 0.4: GitHub Actions CI 骨架

**产出**:`.github/workflows/ci.yml`、`.github/pull_request_template.md`

**参考**:本计划 §3.5

**实现要点**:
- 触发:push to main + PR
- Job:build → unit-tests → integration(仅 PR)→ conformance(仅 PR)
- 缓存 `~/.m2` 加速
- 失败时上传 target/surefire-reports/ 做 artifact

**验收**:
- [ ] 提交一个 trivial PR(如改 README typo),CI 全通
- [ ] 故意引入编译错误,CI 红色并阻止合入

### Step 0.5: `.gitignore` 修正

**产出**:更新 `.gitignore`

**参考**:当前 `target/*.jar` 被误追踪

**实现要点**:
- 忽略 `target/`、`.idea/`、`.vscode/`、`*.iml`、`.DS_Store`
- 已追踪的 jar 用 `git rm --cached` 清理(一次性 cleanup PR)

**验收**:
- [ ] `git status` 不再显示 target/*.jar modified
- [ ] PR diff 只有期望的源码变更

### Phase 0 DoD

- [ ] 新人从 0 开始,按 README.md 的 "Getting Started" 能在 30 分钟内启动完整开发栈
- [ ] CI 所有 gate 能跑
- [ ] 模拟器可对任意 MQTT `single_cmd` 返回响应

---

## Phase 1: 协议骨架与 MQTT 通道

**目标**:把 Envelope/MQTT/MDC/日志/异常处理的全局骨架打好。后续所有功能都在这层之上生长。

**前置条件**:Phase 0 完成

**文档锚点**:协议 §2-§3、架构 §3、§6.3、§9.1

**估时**:1-2 周

### Step 1.1: `opendota-common` 补齐 Payload DTO

**产出**:
```
opendota-common/src/main/java/com/opendota/common/payload/
  ├─ channel/ChannelOpenPayload.java
  ├─ channel/ChannelClosePayload.java
  ├─ channel/ChannelEventPayload.java
  ├─ channel/ChannelReadyPayload.java
  ├─ single/SingleCmdPayload.java
  ├─ single/SingleRespPayload.java
  ├─ common/DoipConfig.java
  ├─ common/Step.java
  ├─ common/Transport.java (enum)
  └─ common/EcuScope.java (小工具)
```

**参考**:协议 §4-§5、`doc/schema/diag_payload.schema.json`

**实现要点**:
- 全部 Java 21 record
- `@JsonInclude(NON_NULL)` 默认
- `Transport` 枚举 `UDS_ON_CAN` / `UDS_ON_DOIP`,@JsonValue 小写
- `EcuScope.normalize(List<String>)` 字典序排序工具(供锁获取用)

**验收**:
- [ ] Jackson 往返测试:`DiagMessage<SingleCmdPayload>` 序列化 → 反序列化,字段无丢失
- [ ] payloadHash 测试用例对 `BatchDiagPayload` 计算,与 schema TV-Hash-01 期望值相等

### Step 1.2: `EnvelopeReader` 二阶段解析

**产出**:`opendota-common/src/main/java/com/opendota/common/envelope/EnvelopeReader.java`

**参考**:协议 §3.1

**实现要点**:
- 先解析 `act` 字段,按 `DiagAction` 路由到对应 payload 类型
- `Map<DiagAction, Class<?>> payloadTypeRegistry`
- 提供 `EnvelopeReader.parse(byte[], Class<T> payloadType): DiagMessage<T>`
- 对未知 act 返回 `DiagMessage<JsonNode>` 并 WARN 日志

**验收**:
- [ ] 单元测试:20+ 种 act 解析出正确的 payload 类型
- [ ] 异常测试:缺 msgId 抛 `IllegalArgumentException`

### Step 1.3: `EnvelopeWriter` + payloadHash 算法

**产出**:
- `opendota-common/src/main/java/com/opendota/common/envelope/EnvelopeWriter.java`
- `opendota-common/src/main/java/com/opendota/common/envelope/DiagPayloadCanonicalizer.java`
- `opendota-common/pom.xml` 加 `io.github.erdtman:java-json-canonicalization:1.1`

**参考**:`doc/schema/payload_hash_canonicalization.md`

**实现要点**:
- 按 JCS(RFC 8785)规范化
- OpenDOTA 预处理:hex lowercase / ecuScope 排序 / 默认值展开
- SHA-256 小写 64 位 hex 输出
- 5 条测试向量(TV-Hash-01 ~ 05)固化到 `src/test/resources/`

**验收**:
- [ ] 5 条测试向量全通
- [ ] Rust 车端团队用相同向量在 `rfc8785-rs` 输出字节级一致

### Step 1.4: `opendota-mqtt` MqttPublisher

**产出**:
```
opendota-mqtt/src/main/java/com/opendota/mqtt/
  ├─ config/MqttProperties.java (@ConfigurationProperties)
  ├─ config/MqttAutoConfiguration.java
  ├─ publisher/MqttPublisher.java
  ├─ publisher/MqttPublishException.java
  └─ publisher/MqttPublishMetrics.java
```

**参考**:协议 §2、架构 §3.1、已有 `application.yml` `opendota.mqtt.*` 配置

**实现要点**:
- 使用 Paho `MqttClient`,单连接 + 自动重连
- `publish(String topic, DiagMessage<?> env, int qos)` 同步阻塞等 PUBACK(QoS 1)
- Prometheus 指标:`dota_mqtt_publish_total`、`dota_mqtt_publish_failed_total`、`dota_mqtt_publish_latency_seconds`
- `opendota.mqtt.enabled=false` 时不初始化 bean(测试友好)

**验收**:
- [ ] 单元测试:mock Paho,验证 topic + payload 正确
- [ ] 集成测试(Testcontainers EMQX):publish 后 subscriber 能收到

### Step 1.5: `opendota-mqtt` MqttSubscriber + Envelope 分派

**产出**:
```
opendota-mqtt/src/main/java/com/opendota/mqtt/
  ├─ subscriber/MqttSubscriber.java
  ├─ subscriber/V2CMessageDispatcher.java
  ├─ subscriber/V2CHandler.java (interface)
  └─ subscriber/MdcBinder.java
```

**参考**:协议 §3.4、架构 §3.1、§9.1

**实现要点**:
- 启动时 SUBSCRIBE `dota/v1/+/+/+` QoS 1,cleanSession=false
- 收到消息 → 解析 Envelope → 按 `act` 分派到 `V2CHandler` bean 列表
- MdcBinder 注入五字段(traceId/msgId/vin/operatorId/tenantId)
- 异常隔离:单条消息处理失败不影响后续

**验收**:
- [ ] 集成测试:模拟器发 `single_resp`,subscriber 的 MDC 在日志中可见
- [ ] 负向测试:payload JSON 格式错误不导致 subscriber 挂掉

### Step 1.6: 全局异常处理 + 响应信封

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/web/
  ├─ GlobalExceptionHandler.java (@RestControllerAdvice)
  ├─ ResponseWrapper.java (@ControllerAdvice)
  └─ ApiError.java (枚举所有 code)
```

**参考**:REST §1.2、§3

**实现要点**:
- `{code, msg, data}` 统一包装
- `ApiError.E40001` 等枚举所有错误码
- Spring `ResponseBodyAdvice` 自动包装,但 `text/event-stream` 不包装(SSE 透传)

**验收**:
- [ ] `GET /api/hello` 返回 `{code:0, msg:"success", data:{...}}`
- [ ] 主动抛 `new BusinessException(ApiError.E40001)` 返回 `{code:40001, msg:"...", data:null}`

### Step 1.7: MDC + 结构化日志

**产出**:
- `opendota-observability/src/main/java/com/opendota/observability/logging/DiagTraceFilter.java`
- `opendota-app/src/main/resources/logback-spring.xml`

**参考**:架构 §9.1

**实现要点**:
- Filter 优先级 `Ordered.HIGHEST_PRECEDENCE`
- `traceparent` 解析 W3C Trace Context
- MDC 字段:`traceId` / `msgId` / `vin` / `operatorId` / `tenantId` / `ticketId`
- logback pattern 按架构 §9.1 样例

**验收**:
- [ ] `curl -H 'traceparent: 00-xxx-yyy-01' /api/hello` 日志含 traceId
- [ ] MQTT V2C 消息的日志自动带 msgId/vin

### Phase 1 DoD

- [ ] 所有 Step 完成
- [ ] 符合性用例 A-1, A-2, A-3, A-4(Envelope 基础)通过
- [ ] 代码覆盖率:`opendota-common` > 80%,`opendota-mqtt` > 70%
- [ ] Prometheus `/actuator/prometheus` 可看到 `dota_mqtt_*` 指标

---

## Phase 2: 单步诊断 E2E

**目标**:打通"前端 POST → MQTT 下发 → 模拟器响应 → Redis Pub/Sub → SSE Push → 前端接收"全链路。这是整个平台的 MVP 骨架。

**前置条件**:Phase 1 完成

**文档锚点**:协议 §4-§5、架构 §3.1、REST §4-§8

**估时**:1 周

### Step 2.1: `opendota-diag` 通道管理

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/channel/
  ├─ ChannelManager.java (内存态,单节点)
  ├─ ChannelController.java
  ├─ ChannelOpenService.java
  └─ ChannelCloseService.java
```

**参考**:协议 §4、REST §4.1-§4.2

**实现要点**:
- `POST /diag/channel/open` 构造 `channel_open` Envelope → MqttPublisher → 立即返回 `{msgId, channelId}`
- `POST /diag/channel/{channelId}/close` 同上
- `ChannelManager` 暂存 `channelId → {vin, ecuName, operator, openedAt}`(Phase 6 改为 Redis + PG 持久化)

**验收**:
- [ ] `curl POST /diag/channel/open` 返回 channelId
- [ ] 模拟器日志收到并回 `channel_event opened`
- [ ] 符合性用例 B-1(ecuScope 缺失拒绝)、B-2(单 ECU 成功)通过

### Step 2.2: `opendota-odx` 骨架 + ODX 读取

**产出**:
```
opendota-odx/src/main/java/com/opendota/odx/
  ├─ entity/OdxVehicleModel.java
  ├─ entity/OdxEcu.java
  ├─ entity/OdxDiagService.java
  ├─ entity/OdxParamCodec.java
  ├─ repository/OdxEcuRepository.java (JdbcTemplate)
  └─ service/OdxService.java
```

**参考**:协议 §13、REST §9.1

**实现要点**:
- `JdbcTemplate` 而非 JPA(避免过早抽象)
- `GET /odx/services?ecuId=123` 返回服务目录树
- `GET /vehicle/{vin}/ecu-scope?ecuName=BMS` 返回网关依赖链

**验收**:
- [ ] Mock 数据下 GET 返回 3 个 ECU + 7 个服务
- [ ] ecu-scope 查询返回 `["BMS","GW"]`(依赖链)

### Step 2.3: `opendota-diag` 单步指令

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/cmd/
  ├─ SingleCmdController.java
  ├─ SingleCmdService.java
  ├─ DiagDispatcher.java
  └─ SingleRespHandler.java (@Component implements V2CHandler)
```

**参考**:协议 §5、REST §4.3

**实现要点**:
- `DiagDispatcher.publish(envelope)` 仅发 MQTT,不等响应
- `SingleRespHandler` 收到 V2C 后:
  1. ODX 翻译 `resData`
  2. 写 `diag_record` 表
  3. 写 `sse_event` 表(含翻译后的 payload_summary)
  4. Redis `PUBLISH dota:resp:{vin}`

**验收**:
- [ ] `curl POST /diag/cmd/single {reqData:"22F190"}` 返回 msgId
- [ ] 1 秒内 `diag_record` 新增一行
- [ ] `sse_event` 新增一行,event_type='diag-result'

### Step 2.4: SSE 推送层

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/sse/
  ├─ SseController.java
  ├─ SseEmitterManager.java
  ├─ SseEventDispatcher.java
  ├─ RedisHealthProbe.java
  └─ FallbackSseScanner.java
```

**参考**:架构 §3.2.1-§3.2.1.2、REST §8

**实现要点**:
- `GET /sse/subscribe/{vin}` 返回 `SseEmitter(0)` 永不超时
- Last-Event-ID header + query 两种来源
- 断线补发从 `sse_event WHERE vin=? AND id > ? LIMIT 1000`
- Redis 健康探针 + 降级直扫

**验收**:
- [ ] 浏览器打开 SSE URL 保持连接
- [ ] POST `/diag/cmd/single` 后 SSE 收到 `diag-result` 事件
- [ ] 手动 kill Redis 后 SSE 仍能收到事件(500ms 延迟)

### Step 2.5: 端到端手动验证脚本

**产出**:`scripts/e2e/single-cmd-demo.sh`

**参考**:本 Phase 2 所有 Step

**实现要点**:
- 启动 docker-compose + 在 `opendota-server/` 目录执行 `mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev`
- curl 开通道 → 单步 → 观察 SSE 输出
- 断言关键字段存在

**验收**:
- [ ] 脚本在干净环境一键跑过
- [ ] CI nightly job 运行通过

### Phase 2 DoD

- [ ] 符合性用例 A-1~A-4, B-1~B-4 全通(7 条)
- [ ] `dota_single_cmd_latency_seconds` Prometheus 指标可见,P95 < 1s(mock 环境)
- [ ] 手动 demo 视频录制,作为后续验收回归基线

---

## Phase 3: 批量诊断与多 ECU 脚本

**目标**:支持 `batch_cmd`(单 ECU 多步)和 `script_cmd`(多 ECU 编排),ecuScope 字典序原子锁基线就位。

**前置条件**:Phase 2 完成

**文档锚点**:协议 §6、§9、§10.2.3

**估时**:1-2 周

### Step 3.1: `batch_cmd` 下发与响应

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/cmd/
  ├─ BatchCmdController.java
  ├─ BatchCmdService.java
  └─ BatchRespHandler.java
```

**参考**:协议 §6、REST §4.4

**实现要点**:
- 复用 `DiagDispatcher.publish`
- `BatchRespHandler` 聚合 `results[]` 翻译后写库
- SSE 事件 `batch-result`

**验收**:
- [ ] `POST /diag/cmd/batch` 4 步任务,模拟器返回后 SSE 收到聚合结果
- [ ] 单步失败 + `strategy=0` 时 `overallStatus=3`

### Step 3.2: ecuScope 字典序原子锁(云端)

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/arbitration/
  ├─ EcuLockRegistry.java (基于 Redisson RLock)
  ├─ EcuLockException.java
  └─ LockOrderPolicy.java
```

**参考**:协议 §10.2.3

**实现要点**:
- 按 `ecuScope` 字典序 `tryLock` 全部,任一失败全部释放
- 超时 5 秒
- 云端锁与车端锁对应(双层)

**验收**:
- [ ] 单元测试:并发 10 个跨 ECU 请求,无死锁
- [ ] 符合性 C-1(跨车环形)、C-2(部分占用)通过

### Step 3.3: `script_cmd` 多 ECU 脚本

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/cmd/
  ├─ ScriptCmdController.java
  ├─ ScriptCmdService.java
  └─ ScriptRespHandler.java
```

**参考**:协议 §9、REST §5

**实现要点**:
- `executionMode: parallel` / `sequential`
- 聚合 `ecus[].ecuName` 生成总 `ecuScope`(去重字典序)
- 响应 `script_resp` 按 ecu 分组

**验收**:
- [ ] `POST /diag/cmd/script` 3 ECU 并行,SSE `script-result` 按 ECU 分组
- [ ] 符合性 B-5(多 ECU 锁)、C-1、C-2 通过

### Step 3.4: 宏指令占位实现

**产出**:
- `opendota-diag` 增加 `macro_security` / `macro_routine_wait` 的下发支持(payload 透传,模拟器已有占位响应)

**参考**:协议 §7

**实现要点**:
- 云端只组装 payload,不处理宏细节(宏逻辑在车端)
- 下发时记 `macroType` 用于审计

**验收**:
- [ ] `batch_cmd` 含 `{type:"macro_security",level:"01"}` 被模拟器接受并"解锁成功"

### Phase 3 DoD

- [ ] 符合性用例 B-1~B-5, C-1~C-2 全通(7 条)
- [ ] 跨 ECU 脚本的 `ecuScope` 锁与释放有日志追溯
- [ ] `batch_resp` / `script_resp` 结果都能在 SSE 上实时显示

---

## Phase 4: 云端任务系统

**目标**:实现文档中最大的一块——云端任务定义、分发、追踪、周期双 ack、离线 replay。

**前置条件**:Phase 3 完成

**文档锚点**:协议 §8、§12、架构 §3.3、§4、§6.2-§6.3

**估时**:2-3 周(本 Phase 最重)

### Step 4.1: task_definition CRUD

**产出**:
```
opendota-task/src/main/java/com/opendota/task/
  ├─ entity/TaskDefinition.java
  ├─ entity/TaskTarget.java
  ├─ entity/TaskDispatchRecord.java
  ├─ repository/TaskDefinitionRepository.java
  ├─ service/TaskService.java
  └─ controller/TaskController.java
```

**参考**:REST §6、协议 §8.2

**实现要点**:
- `POST /task` 校验规则 R1/R2/R3 + payloadHash 计算
- `targetScope.mode=snapshot` 立即解析目标车辆落 `task_dispatch_record`
- `missPolicy` 自动推导(协议 §8.3.4)
- 错误码 41001-41012 全部实现

**验收**:
- [ ] 创建 100 辆车的 batch 任务,`totalTargets=100` 且 dispatch_record 生成 100 行
- [ ] 违反 R3(maxExecutions=-1 无 executeValidUntil)返回 41001

### Step 4.2: Outbox 模式 + Kafka 投递

**产出**:
```
opendota-task/src/main/java/com/opendota/task/outbox/
  ├─ OutboxEvent.java
  ├─ OutboxRelayWorker.java
  ├─ OutboxArchiveJob.java
  └─ OutboxMetrics.java
```

**参考**:架构 §3.3.1 / §3.3.1.1、SQL V5

**实现要点**:
- `POST /task` 同事务内写 `outbox_event`
- `@Scheduled(fixedDelay=200)` Relay Worker,`FOR UPDATE SKIP LOCKED`
- Kafka idempotent producer(`enable.idempotence=true`)
- 指数退避 `next_retry_at = now() + min(attempts,8)*30s`

**验收**:
- [ ] 模拟 Kafka 不可用,Relay 失败但任务写 PG 成功
- [ ] Kafka 恢复后 Relay 在 30 秒内投递成功
- [ ] 监控:`outbox_pending_total{status='failed'}` 从 0 增加再回 0

### Step 4.3: 分发调度器(Dispatcher)

**产出**:
```
opendota-task/src/main/java/com/opendota/task/dispatch/
  ├─ DispatchSource.java            -- 枚举,4 种分发来源
  ├─ DispatchProperties.java        -- @ConfigurationProperties 配置
  ├─ DispatchRateLimiter.java       -- Redisson 4 级令牌桶限流
  ├─ TaskDispatchScheduler.java     -- 条件 jitter 调度器
  ├─ DispatchCommandListener.java   -- @KafkaListener 消费者
  ├─ DispatchMetrics.java           -- Micrometer 指标
  └─ DispatchKafkaConfig.java       -- Kafka 消费者配置
(opendota-task/src/main/java/com/opendota/task/outbox/DispatchCommand.java 复用已有)
```

**参考**:架构 §4.4.1-§4.4.4

**实现要点**:
- Kafka consumer 按 VIN 哈希分区
- 四级 Redisson 限流
- `DispatchSource` 控制是否走 jitter(ONLINE_IMMEDIATE 不走)
- 在线车先下发 MQTT,离线车保持 `pending_online`

**验收**:
- [x] 创建任务 → 在线车 2 秒内收到 MQTT `schedule_set`
- [x] 离线车 dispatch_status=pending_online
- [x] 模拟 Token Bucket 用尽,任务排队不失败

### Step 4.4: 车辆生命周期消费者

**产出**:
```
opendota-task/src/main/java/com/opendota/task/lifecycle/
  ├─ VehicleLifecycleEvent.java
  ├─ OnlineEventConsumer.java
  ├─ VehicleOnlineService.java
  └─ ReconcileOnlineStatusJob.java
```

**参考**:架构 §4.5-§4.6

**实现要点**:
- EMQX Rule Engine 配置 `client.connected/disconnected` → Kafka `vehicle-lifecycle`(部署侧)
- Consumer 更新 `vehicle_online_status` + 触发 pending_online replay
- Reconcile 作业 5 分钟扫描 EMQX REST API 对账

**验收**:
- [ ] 模拟车 `disconnect` 后 PG 标 offline
- [ ] 离线任务创建 → 车辆上线后 SSE 推送 `task-progress`

### Step 4.5: 周期任务双 ack

**产出**:
```
opendota-task/src/main/java/com/opendota/task/execution/
  ├─ ExecutionBeginHandler.java
  ├─ ExecutionEndHandler.java
  ├─ ExecutionReconcileJob.java
  └─ TaskExecutionLogRepository.java
```

**参考**:协议 §8.5.1、架构 §4.9

**实现要点**:
- `execution_begin` INSERT ON CONFLICT DO NOTHING
- `current_execution_count = GREATEST(old, seq)`(V4 触发器保底)
- Reconcile:`begin_reported_at + maxExecutionMs*3` 无 end → mark failed

**验收**:
- [ ] 模拟器发两次相同 execution_begin,PG 只有一行
- [ ] 周期任务跑 10 次,`current_execution_count=10`
- [ ] 符合性用例 D-1, D-2 通过

### Step 4.6: `supersedes` 任务修订

**产出**:
```
opendota-task/src/main/java/com/opendota/task/revise/
  ├─ TaskReviseController.java
  ├─ TaskReviseService.java
  └─ SupersedeDecider.java
```

**参考**:协议 §12.6.3、REST §6.2

**实现要点**:
- 三分支处理(queued/executing 常规/不可中断)
- 新任务 version = old.version + 1
- 车端 `task_ack.status` 为 supersede_accepted/rejected 的处理

**验收**:
- [ ] 修订 queued 任务:旧 canceled,新 queued
- [ ] 修订 executing `macro_data_transfer`:返回 supersede_rejected
- [ ] 符合性 D-1, D-2, D-3 通过

### Step 4.7: DynamicTargetScopeWorker

**产出**:
```
opendota-task/src/main/java/com/opendota/task/scope/
  ├─ DynamicTargetScopeWorker.java
  └─ TargetScopeResolver.java
```

**参考**:架构 §4.4.4.1、SQL V7

**实现要点**:
- `@Scheduled(cron="0 5 * * * ?")` 每小时扫一次
- 对 `mode='dynamic'` 任务补发 dispatch_record
- 走 `DispatchSource.OFFLINE_REPLAY`(新车多是刚上线)

**验收**:
- [ ] 创建 dynamic 模式周期任务,扫描前给车型新增一辆车,下次扫描后新车自动纳入
- [ ] 任务 expired 后 Worker 跳过

### Step 4.8: 进度聚合 API

**产出**:
- `GET /task/{taskId}/progress` 实现(REST §6.4)
- `GET /task/{taskId}/executions`(REST §6.5)
- 视图 `v_task_board_progress`(SQL 已定义,架构 §4.8.1)

**参考**:REST §6.4-§6.5、架构 §4.8.1

**实现要点**:
- 11 状态分布聚合
- `boardStatus` 用视图计算
- 分页 + RLS

**验收**:
- [ ] 任务 5000 目标,部分完成时进度 JSON 11 字段齐全

### Phase 4 DoD

- [ ] 符合性用例 D-1, D-2, D-3 通过
- [ ] 端到端:创建 `periodic` 任务 → 10 分钟内 `task_execution_log` 出现 2 行(cron "0 */5")
- [ ] `offline_task_push_latency P95 < 10s`(模拟器上线场景,100 车)
- [ ] Outbox pending 队列深度监控曲线平稳

---

## Phase 5: 条件任务与车端队列操控

**目标**:把 `schedule_set conditional` 和队列操控(query/delete/pause/resume)打通。

**前置条件**:Phase 4 完成

**文档锚点**:协议 §8.3、§11

**估时**:1-2 周

### Step 5.1: 信号白名单管理

**产出**:
- `opendota-admin` 增加 `POST /admin/odx/models/{modelId}/signal-catalog/publish`
- `opendota-task` 实现 `signal_catalog_push` MQTT 下发
- `SignalCatalogAckHandler` 处理车端 ack

**参考**:协议 §8.3.2-§8.3.2.1、REST §9.3

**实现要点**:
- OSS 预签名 URL(15 分钟有效)
- `signalCatalogVersion` 严格递增校验
- `replaceMode=gradual` 保留旧版 7 天(SQL V6 租户切片已就位)

**验收**:
- [ ] 发布新版白名单后模拟器收到 `signal_catalog_push` 并回 `signal_catalog_ack accepted`
- [ ] 创建条件任务 `signalCatalogVersion` 落后于车端 → 车端入队并向前兼容

### Step 5.2: `schedule_set conditional` 下发

**产出**:`TaskService` 增强 conditional 支持,`SchedulerRespHandler` 处理条件任务结果

**参考**:协议 §8.2.2-§8.2.3、§8.3

**实现要点**:
- `triggerCondition` 5 种 type 全支持
- debounceMs / cooldownMs 只是透传给车端

**验收**:
- [ ] 创建 `power_on` 触发的任务,模拟器重启 → 触发 `condition_fired` → 云端入 condition_fired_log

### Step 5.3: `condition_fired` / `missPolicy` 处理

**产出**:
```
opendota-task/src/main/java/com/opendota/task/condition/
  ├─ ConditionFiredHandler.java
  ├─ MissCompensationTracker.java
  └─ ConditionFiredLogRepository.java
```

**参考**:协议 §8.3.4-§8.3.7

**实现要点**:
- `missCompensation` 字段聚合到 `task_execution_log.miss_compensation`
- 按 `triggerCondition.type` 推导 missPolicy(DTC→fire_all)

**验收**:
- [ ] 符合性用例 E-1, E-2, E-3 通过
- [ ] 符合性用例 I-1, I-2(DTC 保真)通过

### Step 5.4: 队列操控 API

**产出**:
```
opendota-diag(或 opendota-task)/src/main/java/.../queue/
  ├─ QueueController.java
  ├─ QueueService.java
  ├─ QueueStatusHandler.java
  └─ QueueRejectHandler.java
```

**参考**:协议 §11、REST §7

**实现要点**:
- `GET /vehicle/{vin}/queue` 按 `wait` 参数同步/异步
- `queue_query` / `queue_delete` / `queue_pause` / `queue_resume` 发 MQTT
- 缓存到 Redis `queue_cache:{vin}` TTL 10s
- 收到 `queue_status` 广播 SSE

**验收**:
- [ ] `wait=false` 立即返回缓存
- [ ] `wait=true` 等 MQTT 响应或 3s 超时
- [ ] `queue_pause` 后车端状态为 paused(模拟器已支持)

### Step 5.5: 反压候补表

**产出**:
```
opendota-task/src/main/java/com/opendota/task/backlog/
  ├─ PendingBacklogService.java
  ├─ QueueStatusReplayTrigger.java
  └─ BacklogReplayScheduler.java
```

**参考**:协议 §11.8、架构 §4.7、SQL `task_dispatch_pending_backlog`

**实现要点**:
- 收到 `queue_reject` 落 backlog 表
- 订阅 `queue_status`,`queueSize < 0.7*maxQueueSize` 时触发 replay
- 每分钟定时扫描兜底

**验收**:
- [ ] 模拟器队列满返回 reject,云端写 backlog
- [ ] 模拟器完成任务上报 queue_status 空,backlog 中任务 replay 成功

### Phase 5 DoD

- [ ] 符合性用例 E-1~E-3, I-1~I-2 通过
- [ ] 手动 E2E:创建 DTC 触发条件任务 → 模拟器生成 DTC → SSE 收到 condition_fired + schedule_resp

---

## Phase 6: 资源仲裁完备

**目标**:per-ECU 锁、channel_ready、preemptPolicy=wait、force 抢占、自动抢占全部落地。

**前置条件**:Phase 5 完成

**文档锚点**:协议 §10、架构 §4.3.1-§4.3.2

**估时**:1-2 周

### Step 6.1: `diag_channel_pending` 生命周期

**产出**:
```
opendota-diag/src/main/java/com/opendota/diag/arbitration/pending/
  ├─ ChannelPendingService.java
  ├─ ChannelRejectedListener.java
  ├─ ChannelReadyListener.java
  ├─ PendingTimeoutSweeper.java
  └─ ChannelPendingController.java (POST /diag/channel/pending/{channelId}/cancel)
```

**参考**:架构 §4.3.2、SQL V2、REST §4.2.1

**实现要点**:
- `preemptPolicy=wait` 被拒时写 `diag_channel_pending`
- `channel_ready` 到达时 `FOR UPDATE SKIP LOCKED` 抓一个 replay
- 3 分钟超时扫描
- 用户 cancel 端点(v1.4 A5)

**验收**:
- [ ] 车端忙时开通道,云端写 pending 且前端收到 channel-rejected
- [ ] 车端空闲后自动 replay,SSE 收到 channel-ready

### Step 6.2: force 抢占 + 自动抢占

**产出**:
- `ChannelOpenService` 增强 `force=true` 处理
- `AutoPreemptDecider`(架构 §4.3)

**参考**:协议 §10.7.1 / §10.7.5

**实现要点**:
- `force=true` 校验 role ∈ {senior_engineer, admin}
- 自动抢占规则:priority ≤ 2 vs ≥ 6,排除 macro_data_transfer / macro_security 半程
- 审计日志关联抢占者 + 被抢占任务创建者

**验收**:
- [ ] 符合性用例 H-1, H-2, H-3 通过
- [ ] 抢占后审计 chain_id 可追溯

### Step 6.3: workstationId 隔离(DoIP 工位)

**产出**:
- `EcuLockRegistry` 支持 `(workstationId, ecuName)` 二元锁
- REST `channel_open` 接受 `workstationId` 字段

**参考**:协议 §10.8

**实现要点**:
- 锁 key 从 `ecu:{vin}:{ecuName}` 改为 `ecu:{vin}:{workstationId|DEFAULT}:{ecuName}`
- 证书承载 workstationId(Phase 7 配合 mTLS 做强绑定)

**验收**:
- [ ] 两个模拟诊断仪不同 workstationId,操作不同 ECU 并行无冲突
- [ ] 操作同 ECU 时后者被拒 `ECU_LOCKED_BY_OTHER_WORKSTATION`

### Phase 6 DoD

- [ ] 所有资源仲裁相关符合性用例(B/C/H)通过
- [ ] Prometheus `dota_force_preemption_total`、`dota_channel_open_rejected_ratio` 有数据

---

## Phase 7: 安全、审计、可观测

**目标**:生产级安全与可观测基线就位,具备上线审计资格。

**前置条件**:Phase 6 完成

**文档锚点**:协议 §15-§16、架构 §6.2-§6.3、§9

**估时**:1-2 周

### Step 7.1: JWT + RBAC

**产出**:
```
opendota-security/src/main/java/com/opendota/security/
  ├─ auth/JwtService.java
  ├─ auth/AuthController.java (POST /auth/login, /auth/refresh)
  ├─ rbac/RbacEvaluator.java (@Component("rbac"))
  ├─ rbac/PermissionMatrix.java
  ├─ tenant/TenantContext.java (ThreadLocal)
  └─ tenant/TenantRlsAspect.java
```

**参考**:协议 §15.4、REST §2、架构 §6.3.1

**实现要点**:
- JWT HS256 或 RS256,8h 过期
- Redis `jti` 黑名单
- `@PreAuthorize("@rbac.canExecute(...)")` 在所有 `cmd/*` Controller 上
- `TenantRlsAspect` 在 `@Transactional` 方法入口注入 `SET LOCAL app.tenant_id`

**验收**:
- [ ] engineer 尝试 force=true 开通道,返回 40301
- [ ] 跨租户查询(mock 两个 tenant)被 RLS 阻断

### Step 7.2: mTLS + 三元绑定

**产出**:
- 部署侧 EMQX ACL 配置(deploy/emqx/acl.conf)
- `opendota-security/src/main/java/com/opendota/security/mtls/MtlsValidator.java`
- `opendota-mqtt` Subscriber 额外校验 `vin_in_topic == env.vin == cert.CN`

**参考**:协议 §15.1.1

**实现要点**:
- 本地开发用自签证书
- 校验失败写 `security_audit_log` + 告警 `VIN_TOPIC_MISMATCH`

**验收**:
- [ ] 故意发错 VIN 的消息,云端丢弃 + Prometheus 告警触发

### Step 7.3: 审计日志 + WORM 准备

**产出**:
- `SecurityAuditService` 统一入口
- 所有 `cmd/*` / `*_cancel` / 抢占等关键动作写审计
- SQL trigger 模板启用指引(第 8 节注释)

**参考**:协议 §15.2-§15.2.1

**实现要点**:
- 脱敏:预签名 URL、secrets mask
- `chain_id` 链:任务触发时引用创建者的 `audit_id`
- 独立 DB 账号(部署侧)

**验收**:
- [ ] 100 条关键操作全部有审计记录
- [ ] 审计账号尝试 UPDATE security_audit_log 报错(WORM 启用后)

### Step 7.4: Prometheus + OTEL

**产出**:
- `opendota-observability` 增强,所有关键指标埋点
- `application.yml` 打开 `management.endpoints.web.exposure.include`
- OTEL 采样 10%

**参考**:协议 §16、架构 §9.2

**实现要点**:
- SLI 清单(§16.1)全部实现
- Trace 贯穿 HTTP → Kafka → MQTT → Redis(`traceparent` 字段)

**验收**:
- [ ] Grafana 4 个主看板(诊断/任务/仲裁/安全)全部有数据
- [ ] Jaeger 可查询端到端 trace

### Step 7.5: Maker-Checker 审批流

**产出**:
- `opendota-admin` `ApprovalController`
- 任务创建时 `requiresApproval=true` 走审批挂起

**参考**:协议 §15.4.2、REST §11

**实现要点**:
- `requested_by != approved_by` 强制
- 24h 过期
- 审批通过后任务自动 active

**验收**:
- [ ] 固件刷写任务必须审批,未批前车端校验 approvalId 拒绝

### Phase 7 DoD

- [ ] 所有 SLO 指标 Grafana 可见
- [ ] 安全渗透测试清单跑过(跨租户、RBAC 绕过、重放攻击)
- [ ] 审计合规 checklist(§15.2)全过

---

## Phase 8: OTA 与时钟信任

**目标**:`macro_data_transfer` 断点续传 + `time_sync` 系列支持。

**前置条件**:Phase 7 完成

**文档锚点**:协议 §7.5、§17、§15.3

**估时**:1-2 周

### Step 8.1: 固件上传与预签名 URL

**产出**:
- `opendota-admin/firmware/FirmwareController.java`
- OSS / MinIO 客户端集成
- `POST /task/{taskId}/firmware-url/{firmwareId}` 生成预签名

**参考**:协议 §15.3、REST §14

**实现要点**:
- STS 临时凭证 15 分钟
- SHA-256 预计算,存 `firmware` 表

**验收**:
- [ ] 上传 1MB 固件成功,生成预签名 URL 可下载

### Step 8.2: `macro_data_transfer` 协议下发 + `flash_session`

**产出**:
- 任务创建时支持 OTA payload
- `FlashSessionService` 管理 `flash_session` 表

**参考**:协议 §7.5、架构 §6.2

**实现要点**:
- `transferSessionId` 断点续传
- A/B 分区预留字段(硬件未就绪)
- 超时/心跳检查

**验收**:
- [ ] 模拟器支持 OTA 流程(简化版),完整 trace 可在 Jaeger 看到

### Step 8.3: 时钟信任模型

**产出**:
- `TimeSyncController`(若走 HTTP)或 `TimeSyncHandler`
- `VehicleClockTrustService`
- 定时对账作业更新 `trust_status`

**参考**:协议 §17、架构 §6.2

**实现要点**:
- 车端自报 → 云端比对 → 按需下发 `time_sync_request`
- `untrusted` 时定时任务挂起(触发 `CLOCK_UNTRUSTED`)

**验收**:
- [ ] 符合性用例 G-1, G-2, G-3, G-4 通过
- [ ] `GET /vehicle/{vin}/clock-trust` 返回正确状态

### Step 8.4: 聚合报文分片重组

**产出**:
- `ChunkReassemblyService`(架构 §4.4.5)
- 超时告警

**参考**:协议 §8.5.2、SQL `task_result_chunk`

**验收**:
- [ ] 符合性用例 F-1, F-2, F-3, F-4 通过

### Phase 8 DoD

- [ ] 协议附录 D 30 条用例全过(Conformance 100%)
- [ ] OTA 端到端模拟(含回滚决策)demo 通过

---

## Phase 9: 前端 React

**目标**:React 19 + AntD 5 + Vite 6 前端,覆盖诊断控制台、任务看板、SSE 订阅。

**前置条件**:Phase 2 完成可开始(可与 Phase 3-8 并行)

**文档锚点**:架构 §7、REST 全文

**估时**:2-3 周(前端工程师)

### Step 9.1: 工程初始化

**产出**:`opendota-web/` 整个 React 项目 scaffold

**实现要点**:
- Vite + React 19 + TypeScript 5
- AntD 5 + `@antd/icons`
- `openapi-typescript-codegen` 从 `/v3/api-docs` 生成 client
- ESLint + Prettier

### Step 9.2: SSE Hook + 断线补发

**产出**:`src/hooks/useSse.ts`(架构 §7.2 完整实现)

**验收**:
- [ ] 断网 10 秒重连后收到漏掉的事件

### Step 9.3: 诊断控制台

**产出**:`src/pages/DiagPage.tsx` + `components/DiagConsole/*`

**实现要点**:
- 三栏:ECU 树 / 指令面板 / 终端
- 实时 SSE 展示 Hex + 翻译
- Hex viewer 支持复制

### Step 9.4: 任务看板

**产出**:`src/pages/TaskBoardPage.tsx`

**实现要点**:
- 11 状态分布饼图
- 分页列表
- 任务详情侧栏(supersedes 版本链)

### Step 9.5: 登录 + RBAC UI

**产出**:`src/pages/LoginPage.tsx` + 全局权限守卫

**实现要点**:
- JWT 存 localStorage
- 403 / 401 统一跳转

### Phase 9 DoD

- [ ] E2E(Playwright):登录 → 开通道 → 读 VIN → 看到翻译结果
- [ ] 4 个主页面可用:诊断、批量、任务看板、ODX 管理

---

## Phase 10: 压测、DR、上线

**目标**:上线前的最后一道验证关卡。

**前置条件**:Phase 9 完成

**文档锚点**:部署 §3-§6、架构 §12、协议 §15.2.1

**估时**:1-2 周

### Step 10.1: Gatling 压测

**产出**:`perf/gatling-opendota/` 项目

**场景**:
- 10 万车持续在线,5 分钟一次周期任务
- 1 万车 30 秒集中上线(惊群测试)
- 1000 操作员并发诊断

**验收**:
- [ ] 所有 SLO P95 不突破
- [ ] 无 OOM、连接池耗尽

### Step 10.2: DR 演练

**产出**:`runbook/dr-quarterly.md`

**场景**:
- 主 PG 挂 → 故障转移到从
- Redis 集群失联 → SSE fallback 扫描器接管
- Kafka broker 挂 1 台 → min.insync.replicas=2 保障

**验收**:
- [ ] 季度 DR 演练报告生成

### Step 10.3: WORM + 跨区复制启用

**产出**:DBA 脚本执行 + S3 Object Lock 配置

**参考**:协议 §15.2.1

**验收**:
- [ ] security_audit_log UPDATE 被拒
- [ ] S3 跨区复制 RPO < 5 分钟

### Step 10.4: 生产灰度

**产出**:灰度发布 runbook

**流程**:
- 5% 流量(1 个非核心租户)
- 观察 1 周 SLO
- 逐步放量到 50% → 100%

**验收**:
- [ ] 1 个租户生产 7 天无 P0 告警
- [ ] 操作员反馈收集 + 改进列表

### Phase 10 DoD

- [ ] 上线评审会议通过
- [ ] 发布 `v1.0.0` tag

---

## 15. 符合性测试映射矩阵

协议附录 D 30 条用例对 Phase 映射:

| 用例组 | 用例数 | 覆盖 Phase | 负责人 |
|:---|:---:|:---|:---|
| A. Envelope 解析与幂等 | 4 | Phase 1 | 后端 |
| B. ECU 锁获取 | 5 | Phase 2-3 | 后端 |
| C. 多 ECU 死锁防护 | 2 | Phase 3 | 后端 |
| D. 任务 supersedes | 3 | Phase 4 | 后端 |
| E. missPolicy 默认推导 | 3 | Phase 5 | 后端 |
| F. 聚合报文分片 | 4 | Phase 8 | 后端 |
| G. 时钟信任降级 | 4 | Phase 8 | 后端 |
| H. 自动抢占 | 3 | Phase 6 | 后端 |
| I. DTC 保真 | 2 | Phase 5 | 后端 + 车端 |
| **合计** | **30** | | |

每条用例在对应 Phase 的 Step DoD 里必须显式列出,验收时 CI `-Pconformance` 自动跑。

---

## 16. 技术债与未解问题清单

### 16.1 已识别需补(非阻塞,按 Phase 处理)

| # | 技术债 | 影响面 | 处理 Phase |
|:-:|:---|:---|:---:|
| 1 | target/*.jar 被 git 追踪 | 仓库膨胀 | Phase 0 Step 0.5 |
| 2 | V1__baseline_v1_2.sql 单体 25KB | 未来维护 | 保持不拆,新变更走 V8+ |
| 3 | R__mock_seed 生产禁用靠 locations 配置 | 运维风险 | Phase 10 CI grep 兜底 |
| 4 | 前端 React 详细设计未完成 | 前端启动 | Phase 9 开始前补 |
| 5 | Rust 车端真车端实现 | E2E 真车验证 | Phase 10 后另立项目 |
| 6 | `macro_data_transfer` A/B 分区机制 | OTA 完整性 | 等硬件支持,Phase 8 预留 |
| 7 | 周期任务执行速率动态调速 | 高峰期降级 | Phase 10 后迭代 |
| 8 | `task_result_chunk` TTL/GC 定时作业 | 存储膨胀 | Phase 10 观测期 |
| 9 | 条件任务监听基座性能量化 | 车端性能 | 车端联调基准测试 |
| 10 | 操作员离职迁移 × force 抢占边界 | 边缘场景 | Phase 7 细化 |

### 16.2 上线后观察项

- 离线车辆 `pending_online` 积压上限(架构 §12 给的数字是估算)
- EMQX Rule Engine 在百万连接下的 Webhook → Kafka 延迟
- SSE 长连接在 Nginx / L7 LB 超时的实际值
- Outbox Relay 200ms 轮询在低流量时的"空转"能耗

---

## 17. 运维交付物清单

上线前必须齐备的运维资产:

### 17.1 部署资产

- [ ] `deploy/docker-compose.yml`(本地 / 测试)
- [ ] `deploy/charts/opendota/`(Helm,生产)
- [ ] `deploy/kafka-init.sh`
- [ ] `deploy/emqx/acl.conf`
- [ ] `deploy/emqx/rule-engine.sql`(client.connected → Kafka)
- [ ] `deploy/prometheus/opendota-alerts.yml`
- [ ] `deploy/grafana/dashboards/*.json`(4 个主看板)

### 17.2 Runbook

- [ ] `runbook/dr-quarterly.md`(DR 演练流程)
- [ ] `runbook/cert-rotation.md`(mTLS 证书轮换)
- [ ] `runbook/on-call-oncall.md`(值班响应流程)
- [ ] `runbook/odx-publish.md`(ODX 发布 SOP)
- [ ] `runbook/signal-catalog-publish.md`(白名单 OTA)
- [ ] `runbook/firmware-upload.md`(OTA 固件管理)

### 17.3 SLO 看板

按协议 §16 四大类:
- [ ] 诊断链路看板(single/batch/script latency, MQTT 失败率, SSE 补发率)
- [ ] 任务系统看板(dispatch 成功率, pending_online 深度, chunk 重组延迟)
- [ ] 资源仲裁看板(channel 拒绝率, force 抢占, miss compensation)
- [ ] 安全合规看板(证书到期, VIN mismatch, RBAC 拒绝, audit 失败)

### 17.4 告警路由

按 §16.4 三级(P0 电话+IM / P1 IM+邮件 / P2 邮件日报)

---

## 18. 决策记录与变更

### 18.1 v1.4 前定稿决策(design_review.md §6-§8)

见 [`doc/design_review.md`](./design_review.md) §6(v1.1 落地)、§7(v1.2 P0/P1/P2)、§8(v1.3 P0 阻塞)。

### 18.2 v1.4 决策(本计划驱动)

见 [`doc/design_review.md §9`](./design_review.md#九v14-编码启动前二次审阅闭环2026-04-19)。核心 4 项:

1. **A1/B2**: 分层 SLO + 条件 jitter(在线立即 / 离线 replay 走桶)
2. **A2**: 不可中断宏一律拒绝,无 admin 开关
3. **A3**: `targetScope.mode` 双模式(snapshot 默认 / dynamic 仅限 periodic+conditional)
4. **B1**: Phase 0 起 Java 模拟器,不等 Rust Agent

### 18.3 本文件的变更历史

| 版本 | 日期 | 摘要 | 作者 |
|:---:|:---|:---|:---|
| 1.0 | 2026-04-19 | 初版发布,基于 v1.4 基线整理 10 Phase 分解 | Claude + @aircold |

**更新触发条件**:
- 每完成一个 Phase 追加"实际偏差"子节
- 需求变更导致 Phase 顺序调整时 bump 版本
- 新增 Phase(如多区域部署)时 insert

---

> **结语**:本文件不是终极蓝图,是**一份可执行的施工计划**。按 Phase / Step 逐步推进,每合入一个 PR 更新对应 Step 的 "✅" 状态。遇到文档外的决策立即按 AskUserQuestion 模式闭环到 `design_review.md`,避免散落在 commit 消息里。
