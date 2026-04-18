# OpenDOTA 平台技术架构与开发指南

> **版本**: v1.2  
> **日期**: 2026-04-17  
> **状态**: 已定稿(生产级加固版: ECU 级互斥 / 周期计数一致性 / 聚合分片 / 时钟信任),可指导编码  
> **配套文档**: [车云通讯协议规范](./opendota_protocol_spec.md)

---

## 目录

1. [技术选型总览](#1-技术选型总览)
2. [系统架构设计](#2-系统架构设计)
3. [核心异步通信架构](#3-核心异步通信架构)
4. [云端任务管理系统](#4-云端任务管理系统)
5. [后端工程结构设计](#5-后端工程结构设计)
6. [数据库设计](#6-数据库设计)
7. [前端工程设计](#7-前端工程设计)
8. [车端模拟器](#8-车端模拟器)
9. [日志、可观测性与 SLO](#9-日志与可观测性)
10. [MVP 开发路线图](#10-mvp-开发路线图)
11. [开发规约与约定](#11-开发规约与约定)

---

## 1. 技术选型总览

### 1.1 技术栈全景

| 层级 | 技术选型 | 版本要求 | 选型理由 |
|:---|:---|:---:|:---|
| **语言** | Java | **25** | LTS 版本，虚拟线程（Virtual Threads）已全面成熟，性能比肩 Go 协程 |
| **后端框架** | Spring Boot | **4.x** | 原生支持虚拟线程，Jakarta EE 10 全面升级，AOT 编译支持 |
| **MQTT Broker** | EMQX | 5.x | 单机百万级连接，原生集群，内置 Rule Engine 和 Webhook |
| **MQTT Client** | Eclipse Paho | 最新稳定版 | Java 生态最成熟的 MQTT Client，支持 QoS 0/1/2 |
| **关系型数据库** | PostgreSQL | 16+ | JSONB 原生支持，适合 ODX 半结构化编解码规则存储 |
| **时序数据库** | TimescaleDB (PG 插件) | 最新稳定版 | 无缝扩展 PG，零额外运维成本，后期用于诊断报文日志归档 |
| **缓存与消息** | Redis | 7.x | Pub/Sub 通道实现异步结果分发，支持多节点集群广播 |
| **前端框架** | React | 19.x | 组件化架构，生态成熟 |
| **前端 UI 库** | Ant Design | 5.x | 企业级 UI 组件，表格/树形控件/表单完善 |
| **前端构建** | Vite | 6.x | 极速 HMR，开发体验极佳 |
| **API 文档** | SpringDoc (OpenAPI 3) | 最新稳定版 | 自动生成 Swagger UI，前后端联调利器 |

### 1.2 虚拟线程的核心价值

> [!IMPORTANT]
> 本项目选用 Java 25 虚拟线程（Project Loom）的核心原因：在车云诊断场景中，云端下发一条单步 UDS 指令后，需要阻塞等待车端通过 MQTT 异步回调返回结果（通常 1~5 秒）。传统的平台线程（Platform Thread）模型下，每一个等待中的请求都会占用一个操作系统线程（约 1MB 栈空间），100 个并发就是 100MB。而虚拟线程在 I/O 阻塞时会自动释放底层平台线程，百万级虚拟线程的内存开销仅约数十 MB。

**Spring Boot 4 中启用虚拟线程**：

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

启用后，Tomcat 会自动使用虚拟线程处理所有 HTTP 请求，无需修改任何业务代码。

---

## 2. 系统架构设计

### 2.1 整体架构图

```mermaid
graph TB
    subgraph 前端 React App
        UI[诊断控制台 UI]
        SSE_CLIENT[SSE EventSource 客户端]
    end

    subgraph 云端 Spring Boot 集群
        API[REST API Controller]
        DISPATCHER[Diag Dispatcher 分发路由]
        TASK_MGR[任务管理系统 Task Manager]
        TASK_DISP[任务分发调度器 Task Dispatcher]
        MQTT_PUB[MQTT Publisher 发布者]
        MQTT_SUB[MQTT Subscriber 监听者]
        ODX_ENGINE[ODX 编解码引擎]
        SSE_SERVER[SSE Emitter 管理器]
        REDIS_PUB[Redis Publisher]
        REDIS_SUB[Redis Subscriber]
        ONLINE_SVC[车辆在线状态服务]
    end

    subgraph 中间件
        EMQX[EMQX MQTT Broker]
        REDIS[Redis 7.x]
        PG[(PostgreSQL 16)]
    end

    subgraph 车端
        AGENT[车端 Agent / 模拟器]
        TRANSPORT["传输协议栈<br/>ISO-TP (CAN) / DoIP (ETH)"]
    end

    UI -->|HTTP POST 下发指令| API
    API --> ODX_ENGINE
    API --> TASK_MGR
    TASK_MGR --> TASK_DISP
    TASK_DISP --> MQTT_PUB
    ODX_ENGINE --> DISPATCHER
    DISPATCHER --> MQTT_PUB
    MQTT_PUB -->|Publish C2V| EMQX
    EMQX -->|Deliver| AGENT

    AGENT -->|Publish V2C| EMQX
    EMQX -->|Subscribe V2C| MQTT_SUB
    EMQX -->|Webhook: client.connected/disconnected| ONLINE_SVC
    ONLINE_SVC -->|更新在线状态| PG
    ONLINE_SVC -->|触发待推送任务| TASK_DISP
    MQTT_SUB --> ODX_ENGINE
    ODX_ENGINE -->|翻译结果| REDIS_PUB
    REDIS_PUB -->|Pub Channel| REDIS
    REDIS -->|Sub Channel| REDIS_SUB
    REDIS_SUB --> SSE_SERVER
    SSE_SERVER -->|SSE Push| SSE_CLIENT

    API -->|查询服务目录| PG
    ODX_ENGINE -->|读取编解码规则| PG
    MQTT_SUB -->|持久化诊断记录| PG
    TASK_MGR -->|读写任务定义/分发记录| PG
```

### 2.2 核心设计原则

1. **全异步非阻塞**：HTTP 下发触发即返回，结果通过 Redis Channel + SSE 异步推送。
2. **无状态后端**：Spring Boot 节点之间不共享内存状态，所有跨节点通信通过 Redis 通道完成，天然支持水平扩展。
3. **数据库驱动的业务配置**：ODX 导入后持久化到 PG，前端服务目录和翻译规则全部从数据库动态加载。
4. **协议层与业务层严格解耦**：参见 [车云通讯协议规范](./opendota_protocol_spec.md)。

---

## 3. 核心异步通信架构

### 3.1 单步诊断全链路时序

这是整个平台最核心、最高频的交互路径。

```mermaid
sequenceDiagram
    participant React as React 前端
    participant API as Spring Boot API
    participant EMQX as EMQX Broker
    participant Agent as 车端 Agent
    participant Redis as Redis Channel
    participant SSE as SSE Emitter

    React->>API: POST /api/cmd/single (reqData:"22F190")
    API->>API: 生成 msgId, 组装 Envelope
    API->>EMQX: MQTT Publish → dota/v1/cmd/single/{vin}
    API-->>React: HTTP 200 {msgId:"xxx", msg:"已下发"}
    Note over API: 线程立即释放，不等待！

    EMQX->>Agent: 投递指令
    Agent->>Agent: 透传 UDS 到 ECU，获取结果
    Agent->>EMQX: MQTT Publish → dota/v1/resp/single/{vin}

    EMQX->>API: MQTT Subscribe 回调 (可能是集群中的任意节点)
    API->>API: ODX 翻译引擎解码 resData
    API->>Redis: PUBLISH dota:resp:{vin} {translatedResult}

    Redis->>SSE: 所有 Spring Boot 节点收到 Redis 广播
    SSE->>SSE: 查找持有该用户 SSE 连接的节点
    SSE->>React: SSE Push → event: diag-result, data: {翻译后的结果}
    React->>React: 界面实时更新显示结果
```

### 3.2 Redis Pub/Sub 通道设计

| Redis Channel 名称 | 用途 | 消息内容 |
|:---|:---|:---|
| `dota:resp:single:{vin}` | 单步诊断结果广播 | 翻译后的诊断结果 JSON |
| `dota:resp:batch:{vin}` | 批量任务结果广播 | 批量任务汇总结果 JSON |
| `dota:event:channel:{vin}` | 诊断通道状态变更 | 通道开启/关闭/超时事件 |

#### 3.2.1 Pub/Sub 广播丢失风险与缓解策略

> [!WARNING]
> Redis Pub/Sub 是**纯广播模型**——消息不持久化,不为离线订阅者缓存。如果用户的 SSE 连接断开了 10 秒后重连,这 10 秒内通过 Redis Channel 广播的诊断结果会**永久丢失**,用户将看不到这些结果。

#### 3.2.1.1 生产方案:基于 `sse_event` 流水表的多源断线补发

> [!IMPORTANT]
> 早期版本只用 `diag_record` 作为回填源,会**丢失通道事件、任务状态跃迁、条件触发等非 MQTT 响应类事件**。生产级方案要求所有推送给 SSE 的事件都**先落库到统一的 `sse_event` 流水表**,再触发 Redis 广播——这样断线补发只需要针对单表查询,而不用跨多表 join。

**设计约定**:

1. 后端每条 SSE 推送之前,**先 INSERT `sse_event`** 获取自增 ID,这个 ID 用作 SSE `id` 字段(即 Last-Event-ID 的值)。
2. `sse_event` 表是**append-only**,只写不改,冷数据按 30 天滚动删除。
3. 断线补发 SQL 只按 `WHERE vin = ? AND id > ? ORDER BY id ASC LIMIT 1000`,保证补发有序且有上限(避免断线几小时后一次补发爆炸)。
4. 对应的 `diag_record` / `task_execution_log` / `channel_event_log` 记录通过 `sse_event.source_type` + `source_id` 反查,UI 需要详情时再 lazy load。

**表结构**(完整 DDL 见 [6.2 节](#62-任务管理相关表新增)):

```sql
CREATE TABLE sse_event (
    id              BIGSERIAL PRIMARY KEY,
    vin             VARCHAR(17) NOT NULL,
    event_type      VARCHAR(32) NOT NULL,    -- diag-result / channel-event / task-progress / condition-fired
    source_type     VARCHAR(32) NOT NULL,    -- diag_record / task_execution_log / channel_event_log / vehicle_online_status
    source_id       BIGINT NOT NULL,         -- 关联记录的主键
    payload_summary JSONB NOT NULL,          -- 推送给前端的精简数据(完整数据按需 lazy load)
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_sse_event_vin_id ON sse_event(vin, id);
```

```mermaid
sequenceDiagram
    participant React as React 前端
    participant SSE as SSE Emitter
    participant PG as PostgreSQL (sse_event)
    participant Redis as Redis Channel

    Note over React,SSE: 正常状态:SSE 连接存活
    Redis->>SSE: 广播事件 (sse_event.id=100, 任一事件类型)
    SSE->>React: SSE Push (id:100, event:'diag-result', data:{...})

    Note over React,SSE: ❌ 网络断开 10 秒
    Redis->>SSE: 广播事件 (id=101 诊断结果) → 丢失
    Redis->>SSE: 广播事件 (id=102 通道事件) → 丢失
    Redis->>SSE: 广播事件 (id=103 任务进度) → 丢失

    Note over React,SSE: ✅ SSE 重连
    React->>SSE: GET /api/sse/subscribe/{vin} (Last-Event-ID: 100)
    SSE->>PG: SELECT * FROM sse_event WHERE vin = ? AND id > 100 ORDER BY id LIMIT 1000
    PG-->>SSE: 返回 id=101,102,103
    SSE->>React: SSE Push 补发 (id=101, event=diag-result)
    SSE->>React: SSE Push 补发 (id=102, event=channel-event)
    SSE->>React: SSE Push 补发 (id=103, event=task-progress)
    Note over React: 断线期间所有事件类型都已恢复
```

**后端实现要点**:

```java
@GetMapping(value = "/api/sse/subscribe/{vin}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
        @PathVariable String vin,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {

    SseEmitter emitter = new SseEmitter(0L);
    sseEmitterManager.register(vin, emitter);

    if (lastEventId != null && !lastEventId.isBlank()) {
        long lastId = Long.parseLong(lastEventId);
        // 单表查询,按 id 升序补发;单次最多 1000 条防 OOM
        List<SseEvent> missed = sseEventRepository
            .findByVinAndIdGreaterThanOrderByIdAsc(vin, lastId, PageRequest.of(0, 1000));
        for (SseEvent e : missed) {
            emitter.send(SseEmitter.event()
                .id(String.valueOf(e.getId()))
                .name(e.getEventType())
                .data(e.getPayloadSummary()));
        }
        if (missed.size() == 1000) {
            // 超 1000 条未补发完,提示前端整页刷新拉取历史记录
            emitter.send(SseEmitter.event()
                .name("replay-truncated")
                .data(Map.of("suggestFullReload", true, "resumeFromId", missed.getLast().getId())));
        }
    }

    emitter.onCompletion(() -> sseEmitterManager.remove(vin, emitter));
    emitter.onTimeout(() -> sseEmitterManager.remove(vin, emitter));
    return emitter;
}
```

> [!TIP]
> **演进路线**：当系统规模增长到需要支撑高并发、多消费者组时，可从 Redis Pub/Sub 迁移到 **Redis Stream**（`XADD` / `XREAD` / `XACK`），Redis Stream 原生支持消息持久化、消费者组和断点续读，无需再依赖 PG 回填。但 MVP 阶段 PG 回填方案已足够可靠，且零额外组件引入。

### 3.3 SSE (Server-Sent Events) 设计

> [!TIP]
> 选择 SSE 而非 WebSocket 的原因：本场景为典型的**单向推送**（Server → Client），SSE 直接复用 HTTP 协议，无需心跳保活，React 端用原生 `EventSource` 几行代码即可接入，开发维护成本远低于 WebSocket。

**Spring Boot 端**（基础版本，断线补发逻辑见 3.2.1 节）：

```java
@GetMapping(value = "/api/sse/subscribe/{vin}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter subscribe(
        @PathVariable String vin,
        @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    SseEmitter emitter = new SseEmitter(0L); // 永不超时，靠前端重连
    sseEmitterManager.register(vin, emitter);

    // 断线重连补发逻辑（详见 3.2.1 节）
    if (lastEventId != null) {
        sseEmitterManager.replayMissedEvents(vin, lastEventId, emitter);
    }

    emitter.onCompletion(() -> sseEmitterManager.remove(vin, emitter));
    emitter.onTimeout(() -> sseEmitterManager.remove(vin, emitter));
    return emitter;
}
```

**React 端**：

```javascript
useEffect(() => {
  const eventSource = new EventSource(`/api/sse/subscribe/${vin}`);
  
  eventSource.addEventListener('diag-result', (event) => {
    const result = JSON.parse(event.data);
    // 更新诊断结果到界面
    setDiagResult(result);
  });

  eventSource.addEventListener('channel-event', (event) => {
    const channelStatus = JSON.parse(event.data);
    // 更新通道状态
    setChannelStatus(channelStatus);
  });

  return () => eventSource.close();
}, [vin]);
```

---

## 4. 云端任务管理系统

> [!IMPORTANT]
> 云端任务管理系统是「远程诊断 + 批量任务调度平台」的核心上层管理能力。协议层（第 8-12 章）定义了车云之间的通讯报文格式，本章定义**云端侧**的任务编排、分发、追踪和在线状态管理的技术架构。

### 4.1 系统架构概览

```mermaid
graph TB
    subgraph 云端任务管理系统
        TD[任务定义 Task Definition]
        TP[目标车辆匹配 Target Resolver]
        DISP[分发调度器 Task Dispatcher]
        TRACKER[状态追踪器 Status Tracker]
        ONLINE[在线状态服务 Online Service]
    end

    subgraph 外部依赖
        PG[(PostgreSQL)]
        EMQX[EMQX Broker]
        WEBHOOK[EMQX Webhook]
    end

    TD -->|创建任务| TP
    TP -->|解析目标 VIN 列表| DISP
    DISP -->|查询在线状态| ONLINE
    DISP -->|在线车辆: 立即下发| EMQX
    DISP -->|离线车辆: 标记 pending_online| PG
    WEBHOOK -->|client.connected| ONLINE
    ONLINE -->|车辆上线: 查询待推送任务| PG
    ONLINE -->|触发下发| DISP
    EMQX -->|V2C 执行结果| TRACKER
    TRACKER -->|更新分发状态| PG
```

### 4.2 核心数据模型

#### Task(任务定义)

| 字段 | 说明 |
|:---|:---|
| `taskId` | 全局唯一标识(ULID 或 `tsk_` + 12 位 base32,便于日志肉眼读) |
| `taskName` | 任务名称 |
| `version` | 任务版本号,从 1 递增。修改时必须递增,保证幂等可判断 |
| `supersedesTaskId` | 被当前任务替换的旧任务 ID(详见[协议 §12.6.3](./opendota_protocol_spec.md#1263-任务版本替换-supersedes)) |
| `priority` | 优先级 (0=最高, 9=最低) |
| `validFrom` / `validUntil` | 云端下发有效期(协议 `task_definition.valid_until`) |
| `executeValidFrom` / `executeValidUntil` | 车端执行有效期(协议 `scheduleCondition.validWindow`)。默认 `executeValidUntil = validUntil + 7 天` |
| `targetScope` | 目标范围(VIN 列表 / 车型 / 标签 / 全量) |
| `scheduleType` | 调度类型(`once` / `periodic` / `timed` / `conditional`) |
| `scheduleConfig` | 调度配置 JSON |
| `missPolicy` | 错过补偿策略:`skip_all` / `fire_once`(默认) / `fire_all` |
| `payloadType` | 诊断载荷类型(`batch` / `script`) |
| `diagPayload` | 诊断执行内容(`batch_cmd` 或 `script_cmd` 的 payload) |
| `payloadHash` | `diagPayload` 的 SHA-256,用于车端幂等校验(见[协议 §12.6.1](./opendota_protocol_spec.md#1261-车端收到新任务的处理流程)) |
| `requiresApproval` | 是否需要 Maker-Checker 二次审批(见[协议 §15.4.2](./opendota_protocol_spec.md#1542-操作权限表)) |
| `approvalId` | 已批准的审批记录 ID,缺失则不允许下发 |
| `status` | 任务状态(`draft` / `pending_approval` / `active` / `paused` / `completed` / `expired`) |
| `createdBy` | 创建人 operator ID |
| `tenantId` | 租户隔离键 |

#### TaskDispatchRecord(分发记录)

| 字段 | 说明 |
|:---|:---|
| `taskId` | 关联任务 ID |
| `vin` | 目标车辆 |
| `ecuScope` | **v1.2** 任务锁定的 ECU 集合(JSONB,如 `["VCU","BMS"]`),与协议 `ecuScope` 对齐,用于 ECU 级互斥仲裁 |
| `dispatchStatus` | 分发状态(详见 [车云协议 14.4.3 节](./opendota_protocol_spec.md#1443-域-2分发记录状态-task_dispatch_recorddispatch_status)):`pending_online` / `dispatched` / `queued` / `scheduling` / `executing` / `paused` / `deferred` / `superseding` / `completed` / `failed` / `canceled` / `expired` |
| `dispatchedAt` | 实际下发时间 |
| `completedAt` | 完成时间 |
| `resultPayload` | 执行结果 JSON |
| `retryCount` | 已重试次数 |
| `lastError` | 最近一次失败原因(如 `QUEUE_FULL` / `SUPERSEDED` / `ECU_LOST` / `MULTI_ECU_LOCK_FAILED`) |
| `supersededBy` | 被哪个新 taskId 替换(若 status=canceled 且 reason=SUPERSEDED) |
| `currentExecutionCount` | **v1.2** 周期任务已执行次数。云端以 `GREATEST(旧值, 车端 executionSeq)` 更新,车端 SQLite 崩溃不会导致计数倒退 |

### 4.3 任务优先级模型

| 优先级 | 值 | 说明 |
|:---|:---:|:---|
| **在线诊断仪** | 0 | 最高优先级,人工实时操作(隐含,非任务系统管理) |
| 紧急任务 | 1-2 | 紧急召回检测、安全相关自检 |
| 普通任务 | 3-5 | 常规远程诊断、批量 DTC 读取 |
| 低优先级任务 | 6-9 | 数据采集、统计类任务 |

**优先级执行规则**:
- 车端任务队列按 `priority` 值升序排列(数值越小越优先)。
- 相同优先级按入队时间排序(FIFO)。
- 高优先级任务到达时**默认不中断**正在执行的低优先级任务(原因见[协议规范第 10.3 节](./opendota_protocol_spec.md#103-冲突裁决规则))。
- **例外 1**:`senior_engineer` / `admin` 可通过 `channel_open { force: true }` 显式抢占(详见[协议规范 §10.7.1](./opendota_protocol_spec.md#1071-force-抢占流程))。
- **例外 2(v1.2)**:自动抢占矩阵——`priority ≤ 2` 的新任务,对 `priority ≥ 6` 的运行中任务**自动抢占**,不需要角色提升(详见[协议规范 §10.7.5](./opendota_protocol_spec.md#1075-自动抢占规则))。
- 在线诊断仪受特殊互斥规则约束(详见[协议规范第 10 章](./opendota_protocol_spec.md#10-车端资源仲裁与互斥机制-resource-arbitration))。

#### 4.3.1 v1.2 自动抢占决策矩阵

| 新任务 priority | 运行中任务 priority | 运行中任务类型 | 决策 |
|:---:|:---:|:---|:---:|
| ≤ 2 | ≥ 6 | `raw_uds` / `macro_routine_wait`(轮询中) | ✅ 自动抢占,车端发 `31 02` 停止例程,旧任务 re-queue |
| ≤ 2 | ≥ 6 | `macro_security` 半程(已发 27 XX 未完成 27 XX+1) | ❌ 拒绝,等待当前 security 握手完成后再抢占 |
| ≤ 2 | ≥ 6 | `macro_data_transfer` 执行中 | ❌ 拒绝(会 brick ECU) |
| 2 < new ≤ 5 | 任意 | 任意 | ❌ 不自动抢占,正常排队 |
| 任意 | 任意 | 任意(需跨越上表边界) | 必须显式 `force=true` 并校验角色 |

**实现要点**(Java 伪代码):

```java
public PreemptDecision evaluate(Task incoming, Task running) {
    if (incoming.priority() > 2 || running.priority() < 6) {
        return PreemptDecision.NORMAL_QUEUE;
    }
    return switch (running.kind()) {
        case MACRO_DATA_TRANSFER -> PreemptDecision.REJECTED_NON_PREEMPTIBLE;
        case MACRO_SECURITY when running.isMidHandshake()
                -> PreemptDecision.REJECTED_NON_PREEMPTIBLE;
        default -> PreemptDecision.AUTO_PREEMPT;
    };
}
```

### 4.4 大批量车辆分发机制

#### 4.4.1 分发全链路(含 Kafka 可靠管道)

```mermaid
sequenceDiagram
    participant OP as 操作员
    participant API as 任务管理 API
    participant PG as PostgreSQL
    participant KAFKA as Kafka (topic: task-dispatch-queue)
    participant DISP as DispatchWorker (可横向扩展)
    participant LIMIT as 全局限流器 (Redis Token Bucket)
    participant EMQX as EMQX Broker
    participant VEHICLE as 车端 Agent

    OP->>API: 创建任务 (targetScope: 车型=EQ7)
    API->>PG: 写入 task_definition + N 条 task_dispatch_record
    API->>KAFKA: 投递 N 条 DispatchCommand 消息(按 vin 哈希分区)
    API-->>OP: 201 Created, { taskId, totalTargets }

    Note over DISP: 多节点消费,按分区并行处理
    DISP->>KAFKA: poll DispatchCommand
    DISP->>LIMIT: try acquire (按 tenantId + priority 区分限流令牌桶)

    alt 获得令牌
        DISP->>PG: SELECT is_online FROM vehicle_online_status WHERE vin = ?
        alt 车辆在线
            DISP->>EMQX: MQTT Publish dota/v1/cmd/schedule/{vin}
            EMQX-->>DISP: PUBACK (QoS 1)
            DISP->>PG: UPDATE dispatch_status = dispatched
            DISP->>KAFKA: commit offset
        else 车辆离线
            DISP->>PG: 保持 dispatch_status = pending_online
            DISP->>KAFKA: commit offset (等待上线后 replay)
        end
    else 令牌不足
        DISP->>KAFKA: 稍后重试(不 commit,消息会被 re-deliver)
    end
```

**关键设计点**:

1. **API 快速返回**:API 层只写 PG + Kafka,不直接调 MQTT,百万级任务 API 响应 < 1 秒。
2. **按 VIN 分区**:Kafka topic `task-dispatch-queue` 按 `vin` 哈希,同一辆车的任务串行处理,避免并发下发乱序。
3. **水平扩展**:DispatchWorker 加节点即自动增加消费能力,Kafka 消费组自动 rebalance。
4. **失败重试**:DispatchWorker 未 commit offset 前异常 → 消息自动 re-deliver;达到 `retryCount=5` 后转入死信 topic 人工介入。

#### 4.4.2 全局限流器(Redis Token Bucket)

> [!IMPORTANT]
> 单节点限流无效——集群部署下每个节点都限 100 辆/秒,100 节点就是 10000 辆/秒。必须用**分布式限流**:Redis 实现 Token Bucket,所有 DispatchWorker 共用一个 bucket。

| 限流维度 | Key 模板 | 默认配额 | 说明 |
|:---|:---|:---|:---|
| 全局 | `rate:dispatch:global` | 500/秒 | 保护 MQTT Broker |
| 租户 | `rate:dispatch:tenant:{tenantId}` | 100/秒 | 避免单租户挤占全局资源 |
| 优先级 | `rate:dispatch:priority:{0-9}` | P0 200/秒, P1 150/秒, ..., P9 20/秒 | 高优先级有独立配额,不会被低优先级挤占 |
| 单 VIN replay | `rate:dispatch:vin:{vin}` | 10/分钟 | 防止对同一辆车反复重试打爆 MQTT |

```java
// 示例:Token Bucket 获取,使用 Redisson RateLimiter
@Component
public class DispatchRateLimiter {
    private final RedissonClient redisson;

    public boolean tryAcquire(String tenantId, int priority, String vin) {
        if (!rate("rate:dispatch:global").tryAcquire(1, 500, 1, SECONDS)) return false;
        if (!rate("rate:dispatch:tenant:" + tenantId).tryAcquire(1, 100, 1, SECONDS)) return false;
        if (!rate("rate:dispatch:priority:" + priority).tryAcquire(...)) return false;
        if (!rate("rate:dispatch:vin:" + vin).tryAcquire(1, 10, 1, MINUTES)) return false;
        return true;
    }
    // ...
}
```

#### 4.4.3 流量控制策略总览

| 策略 | 实现 | 说明 |
|:---|:---|:---|
| **分批限流** | Redis Token Bucket | 全局/租户/优先级/VIN 四级限流 |
| **分时段下发** | 任务创建时设置 `earliestDispatchAt` | API 入 Kafka 时 delay delivery 到指定时间点 |
| **优先级排序** | Kafka 分区+独立令牌桶 | 每个优先级独立占用 bucket,不会饿死 |
| **失败重试** | DispatchWorker 未 commit offset 触发 re-deliver | 指数退避:1s → 2s → 4s → 8s → 16s,超过 5 次转死信 |
| **死信处理** | 独立 topic `task-dispatch-dlq` + 告警 | 运维介入,分析失败原因后手动 retry |

#### 4.4.4 分桶 Jitter 惊群防护(v1.2)

对应协议 [§13.9](./opendota_protocol_spec.md#139-大规模车辆惊群防护v12)。大停车场清晨上电场景:单靠 Token Bucket 不够,下行 publish 仍是集中爆发。加入 hash 分桶 + random jitter:

```java
@Component
public class TaskDispatchScheduler {
    @Value("${opendota.dispatch.bucket-count:60}")
    private int bucketCount;          // 默认 60 桶
    @Value("${opendota.dispatch.jitter-max-ms:30000}")
    private long jitterMaxMs;         // 30s 窗口错峰

    public void scheduleReplay(String vin, Runnable task) {
        int bucket = Math.abs(vin.hashCode()) % bucketCount;
        long base = bucket * (jitterMaxMs / bucketCount);
        long jitter = ThreadLocalRandom.current().nextLong(0, jitterMaxMs / bucketCount);
        scheduler.schedule(task, base + jitter, MILLISECONDS);
    }
}
```

**Prometheus 监控**:`dota_dispatch_bucket_queue_depth{bucket="$n"}`;任一桶超过 200 深度时告警,说明分桶算法或阈值需要调整。

#### 4.4.5 聚合报文分片消费(v1.2)

对应协议 [§8.5.2](./opendota_protocol_spec.md#852-聚合报文分片协议)。MQTT Subscriber 收到 `schedule_resp { batchUploadMode: "aggregated_chunked" }` 时,不立即解析业务,而是暂存分片:

```java
@Component
public class ChunkReassemblyService {
    private final TaskResultChunkRepository repo;
    private final TaskExecutionLogRepository logRepo;

    @Transactional
    public void onChunk(ScheduleRespChunk chunk) {
        repo.upsert(chunk);  // ON CONFLICT (aggregation_id, chunk_seq) DO NOTHING
        long received = repo.countByAggregationId(chunk.aggregationId());
        if (received >= chunk.chunkTotal()) {
            var merged = repo.loadAllOrdered(chunk.aggregationId());
            logRepo.persistMergedResults(chunk.taskId(), chunk.vin(), merged);
            repo.deleteByAggregationId(chunk.aggregationId());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void alertStaleReassembly() {
        var stale = repo.findAggregationsOlderThan(Duration.ofMinutes(5));
        stale.forEach(agg -> {
            log.warn("chunk reassembly timeout: aggregation_id={}, received={}/{}",
                agg.id(), agg.received(), agg.total());
            metrics.counter("dota_chunk_reassembly_timeout_total").increment();
        });
    }
}
```

**注意事项**:

- 分片乱序到达时,`upsert` 保证幂等,不会丢片
- 车端重传同 `aggregation_id` 的分片时 `ON CONFLICT DO NOTHING` 忽略
- 超过 5 分钟未齐的分片**不清理**,保留给运维人工排查(可能是车端 SQLite 损坏)

### 4.5 离线车辆任务推送机制

> [!IMPORTANT]
> v1.0 版本的"Webhook → 直接调 `dispatchPendingTasks`"模型在万级车辆并发上线时会打爆 Spring Boot(参考"场站充电早晨集中上线"场景)。v1.1 改造为**事件驱动 + Kafka 削峰**模型,保证 10 万辆车 1 分钟集中上线时云端依然平稳。

```mermaid
sequenceDiagram
    participant VEHICLE as 车端 Agent
    participant EMQX as EMQX Broker
    participant RULE as EMQX Rule Engine
    participant KAFKA as Kafka (vehicle-lifecycle)
    participant CONSUMER as OnlineEventConsumer
    participant DISP_KAFKA as Kafka (task-dispatch-queue)
    participant PG as PostgreSQL

    VEHICLE->>EMQX: MQTT CONNECT (username=VIN, mTLS 证书 CN=VIN)
    EMQX->>EMQX: ACL 校验(CN==username==所属 VIN)
    EMQX->>RULE: Rule: client.connected
    RULE->>KAFKA: INSERT into vehicle-lifecycle ({vin, event:"connected", at:...})

    Note over CONSUMER: 分布式消费,按 vin 哈希分区
    CONSUMER->>KAFKA: poll
    CONSUMER->>PG: UPDATE vehicle_online_status SET is_online=true
    CONSUMER->>PG: SELECT * FROM task_dispatch_record WHERE vin=? AND dispatch_status='pending_online' AND task.validUntil > now() ORDER BY priority ASC
    loop 每条待推送任务
        CONSUMER->>DISP_KAFKA: publish DispatchCommand(taskId, vin)
    end
    CONSUMER->>KAFKA: commit offset
```

**幂等保障**:
- 车端 SQLite 已存在的 `taskId` 返回 `task_ack { status: "duplicate_ignored" }`,云端 DispatchWorker 收到后标记 `dispatch_status=queued`(而非重复入队)。
- Kafka `vehicle-lifecycle` 消息按 VIN 分区,避免同一辆车的 connected/disconnected 事件乱序。

### 4.6 心跳、在线状态与可靠事件消费

> [!IMPORTANT]
> v1.0 使用"EMQX Webhook → HTTP POST"是 at-most-once 投递,Spring Boot 崩溃/重启/升级期间事件会**永久丢失**——这是不可接受的。v1.1 采用 **EMQX Rule Engine → Kafka** 模式,事件先可靠落 Kafka,Spring Boot 异步消费,保证 at-least-once 语义。

#### 4.6.1 在线状态维护方案(可靠队列版)

```mermaid
sequenceDiagram
    participant VEHICLE as 车端 Agent
    participant EMQX as EMQX Broker
    participant RULE as EMQX Rule Engine
    participant KAFKA as Kafka vehicle-lifecycle
    participant CONSUMER as OnlineEventConsumer
    participant PG as PostgreSQL

    Note over VEHICLE,EMQX: 车辆上线
    VEHICLE->>EMQX: MQTT CONNECT (with Last Will Topic)
    EMQX->>RULE: client.connected
    RULE->>KAFKA: INSERT VehicleLifecycleEvent{event:"connected"}

    CONSUMER->>KAFKA: poll
    CONSUMER->>PG: UPDATE vehicle_online_status SET is_online=true, last_online_at=now()
    CONSUMER->>CONSUMER: 入库后触发 dispatchPendingTasks(异步)
    CONSUMER->>KAFKA: commit offset

    Note over VEHICLE,EMQX: 正常在线
    loop 平台级心跳 (Keep Alive 60 秒)
        VEHICLE->>EMQX: MQTT PINGREQ
        EMQX-->>VEHICLE: MQTT PINGRESP
    end

    Note over VEHICLE,EMQX: 车辆离线 (异常断连)
    VEHICLE-xEMQX: TCP 断开
    EMQX->>EMQX: 检测到 Keep Alive 超时
    EMQX->>RULE: client.disconnected
    RULE->>KAFKA: INSERT VehicleLifecycleEvent{event:"disconnected"}
    CONSUMER->>KAFKA: poll
    CONSUMER->>PG: UPDATE vehicle_online_status SET is_online=false
```

#### 4.6.2 EMQX Rule Engine 配置(Kafka Sink)

```sql
-- EMQX Dashboard → Rules → Create Rule
-- SQL 语法:从 client.connected / client.disconnected 事件中提取字段投递到 Kafka

SELECT
    clientid as client_id,
    username as vin,
    event as lifecycle_event,
    peername as client_ip,
    cert_subject as cert_info,
    now_timestamp() as event_time
FROM
    "$events/client_connected", "$events/client_disconnected"

-- Action: Kafka Producer
-- Topic: vehicle-lifecycle
-- Partition Strategy: hash by VIN (username)
-- Key: ${username}
-- Payload: JSON
```

#### 4.6.3 可靠消费者实现

```java
/**
 * 车辆生命周期事件消费者
 * 从 Kafka vehicle-lifecycle topic 消费 EMQX 投递的 client.connected / client.disconnected
 * 至少一次语义(at-least-once),消费者幂等
 */
@Component
@KafkaListener(
    topics = "vehicle-lifecycle",
    groupId = "opendota-online-status-consumer",
    concurrency = "8"  // 8 个分区并行消费
)
public class OnlineEventConsumer {

    @KafkaHandler
    public void handle(
            @Payload VehicleLifecycleEvent event,
            Acknowledgment ack) {
        try {
            String vin = event.getVin();
            // 幂等处理:先查当前状态,相同则跳过
            if ("connected".equals(event.getLifecycleEvent())) {
                int updated = vehicleOnlineService.markOnlineIfChanged(
                    vin, event.getClientId(), event.getEventTime());
                if (updated > 0) {
                    // 异步触发 pending_online 任务推送(投递到 task-dispatch-queue)
                    taskDispatchService.enqueuePendingTasks(vin);
                }
            } else if ("disconnected".equals(event.getLifecycleEvent())) {
                vehicleOnlineService.markOfflineIfChanged(vin, event.getEventTime());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("处理车辆生命周期事件失败,将自动重试 vin={}", event.getVin(), e);
            // 不 ack,Kafka 会自动 re-deliver;达到 maxAttempts 后进入 DLQ
            throw e;
        }
    }
}
```

#### 4.6.4 兜底 Reconcile 作业

即便 Kafka 消费可靠,仍有**极低概率**漏事件(如 Rule Engine 故障、Kafka broker 宕机)。加一个定时 Reconcile 作业作为最后防线:

```java
/**
 * 每 5 分钟扫描 EMQX 当前连接列表 API,与 PG 的 is_online 对账
 * 发现不一致则补正
 */
@Scheduled(cron = "0 */5 * * * ?")
public void reconcileOnlineStatus() {
    Set<String> emqxOnline = emqxAdminApi.listConnectedClients();
    List<String> pgOnline = vehicleOnlineService.findAllOnlineVins();
    // 补发 pending tasks 给 EMQX 在线但 PG 未标 online 的车
    emqxOnline.stream()
        .filter(vin -> !pgOnline.contains(vin))
        .forEach(vin -> {
            log.warn("检测到 EMQX 在线但 PG 未记录 vin={}, 触发补正", vin);
            vehicleOnlineService.markOnline(vin, null, Instant.now());
            taskDispatchService.enqueuePendingTasks(vin);
        });
    // 将 EMQX 已离线但 PG 未更新的车标离线
    pgOnline.stream()
        .filter(vin -> !emqxOnline.contains(vin))
        .forEach(vehicleOnlineService::markOffline);
}
```

### 4.7 任务反压候补表(pending backlog)

车端队列满时上报 `queue_reject`(详见[协议 §11.8](./opendota_protocol_spec.md#118-队列满反压协议-queue_full)),云端不能简单放弃重试——需要维护候补表,等车端有空位后 replay。

```mermaid
stateDiagram-v2
    [*] --> Dispatched: DispatchWorker 下发
    Dispatched --> Queued: task_ack accepted
    Dispatched --> PendingBacklog: queue_reject(QUEUE_FULL)
    PendingBacklog --> Dispatched: queue_status 空闲\n(<= 70% 水位)
    Dispatched --> Failed: retryCount > 5
    PendingBacklog --> Expired: validUntil 到期
```

**候补表触发 replay 的两个入口**:
1. **主动**:`QueueStatusConsumer` 订阅车端 `queue_status`,当 `queueSize < maxQueueSize*0.7` 时查询 backlog 按优先级 replay。
2. **被动**:定时作业 `replayPendingBacklog` 每分钟扫描 `backlog.enqueued_at > 60s ago` 的记录,按优先级重试。

### 4.8 任务状态聚合看板

任务管理系统需提供任务维度的执行进度看板 API:

| API | 说明 |
|:---|:---|
| `GET /api/task/{taskId}/progress` | 单个任务的分发进度聚合 |
| `GET /api/task/list` | 任务列表(分页、筛选) |
| `GET /api/task/{taskId}/versions` | 任务版本链(supersedes 追溯) |
| `POST /api/task/{taskId}/revise` | 修改任务(自动生成 supersedes 关系) |

**进度聚合响应示例**:

```json
{
  "taskId": "task-dtc-scan-001",
  "taskName": "全车型 DTC 扫描",
  "version": 2,
  "supersedesTaskId": "task-dtc-scan-000",
  "totalTargets": 5000,
  "progress": {
    "pending_online": 1200,
    "dispatched": 300,
    "queued": 500,
    "scheduling": 20,
    "executing": 150,
    "paused": 30,
    "deferred": 20,
    "completed": 2700,
    "failed": 50,
    "canceled": 10,
    "expired": 40
  },
  "completionRate": "54.0%"
}
```

> [!NOTE]
> 进度字段的状态值与 `task_dispatch_record.dispatch_status` 的 11 个枚举值一一对应。详见 [车云协议 14.4.3 节](./opendota_protocol_spec.md#1443-域-2分发记录状态-task_dispatch_recorddispatch_status)。

#### 4.8.1 `task_definition.status` 终态判定规则(v1.2)

`task_definition.status` 从 `active` 迁移到 `completed` **必须**满足:

```sql
-- 伪代码: 定时扫描作业 (每分钟)
UPDATE task_definition t
SET status = 'completed'
WHERE t.status = 'active'
  AND NOT EXISTS (
      SELECT 1 FROM task_dispatch_record r
      WHERE r.task_id = t.task_id
        AND r.dispatch_status NOT IN ('completed','failed','canceled','expired')
  )
  AND NOT EXISTS (
      SELECT 1 FROM task_dispatch_record r
      WHERE r.task_id = t.task_id
        AND r.dispatch_status = 'pending_online'
  );
```

**关键点**:

- 所有 `task_dispatch_record` 必须到达 4 终态之一
- 存在任何 `pending_online`(即使是过期的)都不视为 completed,只有 `valid_until` 触发 expired 后才计入终态
- `partial_completed` **不是数据库状态**,仅存在于看板视图:

```sql
CREATE VIEW v_task_board_progress AS
SELECT
    task_id,
    SUM(CASE WHEN dispatch_status = 'completed' THEN 1 ELSE 0 END) AS completed_cnt,
    SUM(CASE WHEN dispatch_status = 'failed'    THEN 1 ELSE 0 END) AS failed_cnt,
    SUM(CASE WHEN dispatch_status = 'expired'   THEN 1 ELSE 0 END) AS expired_cnt,
    SUM(CASE WHEN dispatch_status = 'canceled'  THEN 1 ELSE 0 END) AS canceled_cnt,
    COUNT(*) AS total_cnt,
    CASE
        WHEN SUM(CASE WHEN dispatch_status NOT IN ('completed','failed','canceled','expired') THEN 1 ELSE 0 END) > 0
             THEN 'in_progress'
        WHEN SUM(CASE WHEN dispatch_status = 'completed' THEN 1 ELSE 0 END) = COUNT(*)
             THEN 'all_completed'
        WHEN SUM(CASE WHEN dispatch_status = 'completed' THEN 1 ELSE 0 END) > 0
             THEN 'partial_completed'
        ELSE 'all_failed'
    END AS board_status
FROM task_dispatch_record
GROUP BY task_id;
```

### 4.9 周期任务计数一致性实现(v1.2)

> [!IMPORTANT]
> 对应协议 [§8.5.1](./opendota_protocol_spec.md#851-周期任务执行双-ack-execution_begin--execution_end)。车端 SQLite 崩溃或迁移重装时 `currentExecutionCount` 可能丢失或回退;云端必须作为**权威计数器**抵御这种数据回退。

#### 4.9.1 幂等 upsert

Spring Boot 收到 `execution_begin` 时:

```java
@Transactional
public void onExecutionBegin(ExecutionBeginPayload p, String tenantId) {
    // 幂等插入 task_execution_log
    jdbc.update("""
        INSERT INTO task_execution_log
            (task_id, tenant_id, vin, execution_seq, trigger_time, begin_msg_id, begin_reported_at)
        VALUES (?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (task_id, vin, execution_seq) DO NOTHING
        """, p.taskId(), tenantId, p.vin(), p.executionSeq(),
             Timestamp.from(Instant.ofEpochMilli(p.triggerAt())),
             p.msgId());

    // 计数只升不降 -- 关键约束
    jdbc.update("""
        UPDATE task_dispatch_record
        SET current_execution_count = GREATEST(current_execution_count, ?),
            last_reported_at = now()
        WHERE task_id = ? AND vin = ?
        """, p.executionSeq(), p.taskId(), p.vin());
}
```

#### 4.9.2 孤儿 execution_begin 对账

`execution_begin` 后若 `maxExecutionMs × 3` 内未收到 `execution_end`,cron 作业补齐:

```java
@Scheduled(fixedDelay = 60000)
public void reconcileStaleExecutions() {
    jdbc.update("""
        UPDATE task_execution_log
        SET overall_status = 2,
            end_reported_at = now(),
            result_payload = jsonb_build_object('reason', 'EXECUTION_TIMEOUT')
        WHERE end_reported_at IS NULL
          AND begin_reported_at < now() - INTERVAL '1 hour'
        """);
}
```

指标:`dota_execution_reconcile_total{ reason="TIMEOUT" }`,突增告警。

### 4.10 车端时钟信任模型实现(v1.2)

对应协议 [§17](./opendota_protocol_spec.md#17-车端时钟信任模型v12)。

#### 4.10.1 Spring Bean 划分

```
opendota-task/
 └── clock/
      ├── TimeSyncController.java          -- REST: GET /api/time/authoritative (返回权威时间)
      ├── TimeSyncMqttHandler.java         -- 订阅 dota/v1/resp/time/+, 处理 time_sync_response
      ├── VehicleClockTrustService.java    -- 读写 vehicle_clock_trust, 计算 trust_status
      └── ClockDegradationGuard.java       -- 拦截器: 检查 trust_status 决定是否执行定时任务
```

#### 4.10.2 漂移判定核心逻辑

```java
public TrustStatus evaluate(String vin, long vehicleRtcAtMs, long rttEstimateMs) {
    long cloudNow = Instant.now().toEpochMilli();
    long oneWayLatencyMs = rttEstimateMs / 2;
    long driftMs = vehicleRtcAtMs - (cloudNow - oneWayLatencyMs);
    long absDrift = Math.abs(driftMs);
    long maxDrift = clockConfig.getMaxDriftMs();  // 默认 60000

    TrustStatus status;
    if (absDrift <= maxDrift) {
        status = TrustStatus.TRUSTED;
    } else if (absDrift <= 3 * maxDrift) {
        status = TrustStatus.DRIFTING;
    } else {
        status = TrustStatus.UNTRUSTED;
    }
    repo.upsertClockTrust(vin, vehicleRtcAtMs, driftMs, status);
    return status;
}
```

#### 4.10.3 定时任务挂起钩子

`TaskScheduler` 在 dispatch 定时任务前先查 `vehicle_clock_trust`:

```java
if (clockTrustService.getStatus(vin) == TrustStatus.UNTRUSTED
        && task.scheduleMode() == ScheduleMode.TIMED) {
    // 不下发,直接标记 dispatch_record
    dispatchRepo.markDeferred(task.taskId(), vin, "CLOCK_UNTRUSTED");
    alertService.warn("task %s on %s deferred due to untrusted clock", task.taskId(), vin);
    return;
}
```

周期任务仍可下发(车端基于单调时钟执行)。

---

## 5. 后端工程结构设计

### 5.1 Maven 多模块结构

```
opendota-server/
├── pom.xml                          # 父 POM (Spring Boot 4.x Parent)
│
├── opendota-common/                 # 公共模块：通用模型、枚举、工具类
│   └── src/main/java/
│       └── com.opendota.common/
│           ├── model/
│           │   ├── DiagMessage.java          # 信封协议 Envelope Java Bean
│           │   ├── DiagAction.java           # act 枚举定义
│           │   ├── SingleCmdPayload.java     # 单步指令 Payload
│           │   ├── BatchCmdPayload.java      # 批量任务 Payload
│           │   └── ...
│           ├── enums/
│           │   ├── DiagStatus.java           # 通用状态码枚举
│           │   └── MacroType.java            # 宏类型枚举
│           └── util/
│               ├── HexUtils.java             # Hex 编解码工具
│               └── UdsUtils.java             # UDS 报文解析工具
│
├── opendota-mqtt/                   # MQTT 通信模块
│   └── src/main/java/
│       └── com.opendota.mqtt/
│           ├── config/
│           │   └── MqttConfig.java           # MQTT 连接配置与 Bean 定义
│           ├── publisher/
│           │   └── MqttDiagPublisher.java     # 诊断指令发布者
│           └── subscriber/
│               └── MqttDiagSubscriber.java    # 诊断结果订阅监听者
│
├── opendota-odx/                    # ODX 引擎模块
│   └── src/main/java/
│       └── com.opendota.odx/
│           ├── importer/
│           │   └── OdxImportService.java      # ODX 文件导入与持久化
│           ├── encoder/
│           │   └── OdxEncoderService.java     # 下行编码：业务意图 → rawHex
│           ├── decoder/
│           │   └── OdxDecoderService.java     # 上行解码：resData → 人类可读
│           └── translator/
│               └── UdsTranslator.java         # 全量 UDS 服务翻译器
│
├── opendota-diag/                   # 诊断业务核心模块
│   └── src/main/java/
│       └── com.opendota.diag/
│           ├── controller/
│           │   ├── SingleDiagController.java  # 单步诊断 API
│           │   ├── BatchDiagController.java   # 批量诊断 API
│           │   ├── ScriptDiagController.java  # 多 ECU 编排脚本 API
│           │   ├── ScheduleController.java    # 定时任务 API
│           │   └── SseController.java         # SSE 订阅端点
│           ├── service/
│           │   ├── DiagDispatcher.java        # 诊断分发路由中间件
│           │   ├── ChannelManager.java        # 诊断通道生命周期管理
│           │   └── TaskManager.java           # 批量/定时任务状态管理
│           └── sse/
│               └── SseEmitterManager.java     # SSE 连接池管理
│
├── opendota-admin/                  # 管理后台模块 (ODX 导入、车型管理)
│   └── src/main/java/
│       └── com.opendota.admin/
│           ├── controller/
│           │   ├── OdxManageController.java   # ODX 文件上传与管理
│           │   ├── VehicleModelController.java # 车型管理
│           │   └── EcuController.java         # ECU 管理
│           └── service/
│               └── ...
│
├── opendota-security/               # 安全 / 身份 / 权限(v1.1 新增)
│   └── src/main/java/
│       └── com.opendota.security/
│           ├── model/
│           │   ├── Operator.java           # Envelope.operator record
│           │   └── OperatorRole.java
│           ├── service/
│           │   ├── RbacService.java        # @rbac.canExecute(act) SpEL 支持
│           │   ├── TenantContext.java      # RLS tenant_id 注入
│           │   └── ApprovalService.java    # Maker-Checker 审批
│           ├── filter/
│           │   ├── OperatorInjectionFilter.java  # 从 JWT 提取 operator 到 Envelope
│           │   └── EnvelopeSigningFilter.java    # Payload HMAC-SHA256 签名
│           ├── audit/
│           │   └── SecurityAuditService.java     # 独立账号写 security_audit_log
│           └── mtls/
│               └── CertificateValidator.java    # 验证 CN/Tenant/VIN 三元一致
│
├── opendota-observability/          # 可观测性(v1.1 新增)
│   └── src/main/java/
│       └── com.opendota.observability/
│           ├── metrics/
│           │   ├── DiagMetrics.java       # 协议 §16.1.1 的 SLI 指标埋点
│           │   ├── TaskMetrics.java       # §16.1.2
│           │   ├── ArbitrationMetrics.java # §16.1.3
│           │   └── SecurityMetrics.java   # §16.1.4
│           ├── tracing/
│           │   └── OtelPropagator.java    # Envelope.traceparent 跨端传播
│           └── sse/
│               └── SseEventWriter.java    # 统一 sse_event 落库 + Redis PUB
│
├── opendota-task/                   # 任务管理模块(v1.1 重要扩展)
│   └── src/main/java/
│       └── com.opendota.task/
│           ├── controller/
│           │   ├── TaskDefinitionController.java
│           │   ├── TaskDispatchController.java
│           │   └── VehicleQueueController.java
│           ├── service/
│           │   ├── TaskDefinitionService.java
│           │   ├── TaskDispatchService.java
│           │   ├── DispatchWorker.java            # 消费 task-dispatch-queue Kafka
│           │   ├── OnlineEventConsumer.java       # 消费 vehicle-lifecycle Kafka
│           │   ├── QueueStatusConsumer.java       # 订阅车端 queue_status 处理 backlog
│           │   ├── BacklogReplayScheduler.java    # 候补表 replay 定时作业
│           │   ├── ReconcileScheduler.java        # EMQX vs PG 对账定时作业
│           │   ├── DispatchRateLimiter.java       # 四级 Redis Token Bucket
│           │   └── VehicleOnlineService.java
│           ├── listener/
│           │   └── V2CRespListener.java           # MQTT V2C 结果处理,更新 dispatch_status
│           └── model/
│               ├── TaskDefinition.java
│               ├── TaskDispatchRecord.java
│               ├── TaskDispatchPendingBacklog.java
│               └── VehicleOnlineStatus.java
│
└── opendota-app/                    # 启动模块 (聚合打包)
    ├── src/main/java/
    │   └── com.opendota/
    │       └── OpenDotaApplication.java
    └── src/main/resources/
        ├── application.yml
        └── application-dev.yml
```

### 5.2 核心 Java Bean 定义

#### 信封协议（Envelope）

```java
/**
 * 车云通讯统一消息信封
 * 所有 MQTT 交互报文的最外层 JSON 结构
 *
 * @param <T> Payload 业务数据泛型
 */
public record DiagMessage<T>(
    String msgId,       // 全局唯一消息 ID (UUID)
    Long timestamp,     // 毫秒级时间戳
    String vin,         // 17 位车架号
    DiagAction act,     // 业务动作枚举
    T payload           // 具体业务数据
) {
    /**
     * 工厂方法：创建下发消息
     */
    public static <T> DiagMessage<T> of(String vin, DiagAction act, T payload) {
        return new DiagMessage<>(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            vin,
            act,
            payload
        );
    }
}
```

#### 动作类型枚举

```java
/**
 * 车云通讯动作类型 - 所有协议层 act 枚举,与 opendota_protocol_spec.md §3.4 同步
 */
public enum DiagAction {
    // 通道管理
    CHANNEL_OPEN("channel_open"),
    CHANNEL_CLOSE("channel_close"),
    CHANNEL_EVENT("channel_event"),
    CHANNEL_READY("channel_ready"),       // v1.1 新增:车端资源空闲主动通知

    // 单步诊断
    SINGLE_CMD("single_cmd"),
    SINGLE_RESP("single_resp"),

    // 批量诊断
    BATCH_CMD("batch_cmd"),
    BATCH_RESP("batch_resp"),

    // 定时/条件任务
    SCHEDULE_SET("schedule_set"),
    SCHEDULE_CANCEL("schedule_cancel"),   // 保留别名,推荐 TASK_CANCEL
    SCHEDULE_RESP("schedule_resp"),

    // 多 ECU 编排脚本
    SCRIPT_CMD("script_cmd"),
    SCRIPT_RESP("script_resp"),

    // 车端队列操控
    QUEUE_QUERY("queue_query"),
    QUEUE_DELETE("queue_delete"),
    QUEUE_PAUSE("queue_pause"),
    QUEUE_RESUME("queue_resume"),
    QUEUE_STATUS("queue_status"),
    QUEUE_REJECT("queue_reject"),         // v1.1 新增:队列满反压

    // 任务控制
    TASK_PAUSE("task_pause"),
    TASK_RESUME("task_resume"),
    TASK_CANCEL("task_cancel"),
    TASK_QUERY("task_query"),
    TASK_ACK("task_ack"),                 // v1.1 新增:任务接收确认(含幂等)

    // 条件任务事件
    CONDITION_FIRED("condition_fired"),   // v1.1 新增:条件触发命中

    // v1.2 新增: 周期任务执行双 ack (§8.5.1)
    EXECUTION_BEGIN("execution_begin"),
    EXECUTION_END("execution_end"),

    // v1.2 新增: 车端时钟信任模型 (§17)
    TIME_SYNC_REQUEST("time_sync_request"),
    TIME_SYNC_RESPONSE("time_sync_response");

    @JsonValue
    private final String value;
    // ...
}
```

#### Operator 操作者上下文 Record

```java
/**
 * 操作者上下文,注入到 Envelope.operator 字段
 * 与 opendota_protocol_spec.md §3.3 对齐
 */
public record Operator(
    String id,
    OperatorRole role,
    String tenantId,
    String ticketId
) {
    public static Operator from(Authentication auth, String ticketId) {
        JwtPrincipal jwt = (JwtPrincipal) auth.getPrincipal();
        return new Operator(
            jwt.getSubject(),
            OperatorRole.valueOf(jwt.getClaim("role")),
            jwt.getClaim("tenantId"),
            ticketId
        );
    }

    /**
     * 系统操作者,用于任务触发的周期性下发、上线事件处理等非人工触发场景
     */
    public static Operator system(String tenantId) {
        return new Operator("system", OperatorRole.SYSTEM, tenantId, null);
    }
}

public enum OperatorRole {
    VIEWER, ENGINEER, SENIOR_ENGINEER, ADMIN, SYSTEM;
}
```

---

## 6. 数据库设计

### 6.1 PostgreSQL 核心表

详细的 ODX 表结构定义见 [车云通讯协议规范 - 13.3.2 节](./opendota_protocol_spec.md)。此处补充工程实现相关的表。

> [!IMPORTANT]
> v1.1 起所有业务表新增 `tenant_id` 列,并在核心查询加租户过滤的 RLS (Row-Level Security) 策略。多租户隔离是企业级合规的硬性要求。

#### 诊断记录表 (`diag_record`)

用于持久化每一次诊断交互的完整记录(含请求和响应),并作为审计链的主锚点。

```sql
CREATE TABLE diag_record (
    id                BIGSERIAL PRIMARY KEY,
    msg_id            VARCHAR(64) NOT NULL UNIQUE,       -- 消息唯一 ID
    tenant_id         VARCHAR(64) NOT NULL,              -- 租户隔离
    vin               VARCHAR(17) NOT NULL,              -- 车架号
    ecu_name          VARCHAR(64),                       -- ECU 名称
    act               VARCHAR(32) NOT NULL,              -- 动作类型
    req_raw_hex       TEXT,                              -- 下发的原始 Hex
    res_raw_hex       TEXT,                              -- 上报的原始 Hex
    translated        JSONB,                             -- 翻译后的结构化结果
    status            INT DEFAULT -1,                    -- 执行状态码 (-1=等待中)
    error_code        VARCHAR(32),                       -- 错误码
    operator_id       VARCHAR(64),                       -- 操作人员 ID (FK -> operator.id)
    operator_role     VARCHAR(32),                       -- 操作人员角色(冗余便于审计筛选)
    ticket_id         VARCHAR(64),                       -- 关联工单号
    task_id           VARCHAR(64),                       -- FK -> task_definition.task_id (为任务触发的下发链路关联)
    execution_seq     INT,                               -- 该任务第 N 次执行时产生的诊断(周期任务场景)
    script_id         VARCHAR(64),                       -- 关联的 script_cmd.scriptId (多 ECU 脚本场景)
    trace_id          VARCHAR(32),                       -- OpenTelemetry traceId,用于跨服务追踪
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    responded_at      TIMESTAMP                          -- 收到响应的时间
);

-- 索引:按 VIN + 时间范围查询
CREATE INDEX idx_diag_record_vin_time ON diag_record(vin, created_at DESC);
-- 索引:按 msgId 精确查找(用于 MQTT 回调匹配)
CREATE UNIQUE INDEX idx_diag_record_msgid ON diag_record(msg_id);
-- 索引:按 task + 执行序号查找(审计任务执行链)
CREATE INDEX idx_diag_record_task ON diag_record(task_id, execution_seq) WHERE task_id IS NOT NULL;
-- 索引:按租户 + 操作者查询(权限审计)
CREATE INDEX idx_diag_record_tenant_op ON diag_record(tenant_id, operator_id, created_at DESC);
```

#### 批量任务表 (`batch_task`)

```sql
CREATE TABLE batch_task (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    ecu_name          VARCHAR(64),
    overall_status    INT DEFAULT -1,                    -- -1=未开始, 0=全部成功, 1=部分成功, 2=全部失败, 3=终止, 4=CANCELED, 5=DEFERRED
    total_steps       INT NOT NULL,
    strategy          INT DEFAULT 1,                     -- 0=遇错终止, 1=遇错继续
    request_payload   JSONB NOT NULL,
    result_payload    JSONB,
    operator_id       VARCHAR(64),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP
);
```

### 6.2 任务管理相关表(新增)

#### 操作者与角色表 (`operator`, `role`, `operator_role`)

> [!IMPORTANT]
> Operator 表是审计链的起点。**所有审计相关的外键都指向这里,一旦用户离职不允许物理删除,只能 `status=disabled`**。

```sql
-- 角色定义(只读字典)
CREATE TABLE role (
    id                SERIAL PRIMARY KEY,
    role_name         VARCHAR(32) NOT NULL UNIQUE,       -- viewer / engineer / senior_engineer / admin / system
    description       TEXT
);

INSERT INTO role (role_name, description) VALUES
    ('viewer', '只读查看,售后客服'),
    ('engineer', '一线诊断工程师,可执行常规诊断'),
    ('senior_engineer', '资深工程师,可 force 抢占、取消他人任务'),
    ('admin', '平台管理员,可刷写 ECU 固件、导入 ODX、导出审计'),
    ('system', '系统账号,任务触发的下发使用');

-- 操作者主表
CREATE TABLE operator (
    id                VARCHAR(64) PRIMARY KEY,           -- 与 JWT sub claim 一致
    tenant_id         VARCHAR(64) NOT NULL,              -- 租户隔离
    display_name      VARCHAR(128) NOT NULL,
    email             VARCHAR(256),
    phone             VARCHAR(32),
    status            VARCHAR(16) DEFAULT 'active',      -- active / disabled / locked
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_at     TIMESTAMP,
    disabled_at       TIMESTAMP,
    UNIQUE (tenant_id, email)
);
CREATE INDEX idx_operator_tenant ON operator(tenant_id, status);

-- 操作者 ↔ 角色(多对多)
CREATE TABLE operator_role (
    operator_id       VARCHAR(64) REFERENCES operator(id),
    role_id           INT REFERENCES role(id),
    granted_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    granted_by        VARCHAR(64) REFERENCES operator(id),
    PRIMARY KEY (operator_id, role_id)
);

-- 审批流记录(Maker-Checker,用于固件刷写等高危操作)
CREATE TABLE approval_record (
    id                BIGSERIAL PRIMARY KEY,
    approval_id       VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    action            VARCHAR(64) NOT NULL,              -- 动作类型(如 firmware_flash)
    resource_ref      VARCHAR(128) NOT NULL,             -- 资源引用(如 task_id / firmware_id)
    requested_by      VARCHAR(64) REFERENCES operator(id),
    requested_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    approved_by       VARCHAR(64) REFERENCES operator(id),
    approved_at       TIMESTAMP,
    status            VARCHAR(16) DEFAULT 'pending',     -- pending / approved / rejected / expired
    expires_at        TIMESTAMP,                         -- 审批有效期(通常 24 小时)
    approval_note     TEXT,
    CHECK (requested_by != approved_by)                  -- Maker != Checker 的强约束
);
CREATE INDEX idx_approval_status ON approval_record(status, expires_at);
```

#### 任务定义表 (`task_definition`)

```sql
CREATE TABLE task_definition (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    task_name         VARCHAR(256) NOT NULL,
    version           INT NOT NULL DEFAULT 1,            -- 任务版本号,修改时递增
    supersedes_task_id VARCHAR(64),                      -- 被本任务替换的旧 taskId
    priority          INT DEFAULT 5 CHECK (priority BETWEEN 0 AND 9),
    valid_from        TIMESTAMP,
    valid_until       TIMESTAMP,                         -- 云端可下发有效期
    execute_valid_from  TIMESTAMP,                       -- 车端可执行有效期开始(协议 validWindow.startTime)
    execute_valid_until TIMESTAMP,                       -- 车端可执行有效期截止(协议 validWindow.endTime)
    schedule_type     VARCHAR(32) NOT NULL,              -- once/periodic/timed/conditional
    schedule_config   JSONB NOT NULL,                    -- scheduleCondition JSON
    miss_policy       VARCHAR(32) DEFAULT 'fire_once',   -- skip_all / fire_once / fire_all
    payload_type      VARCHAR(32) DEFAULT 'batch',       -- batch / script
    diag_payload      JSONB NOT NULL,
    payload_hash      CHAR(64) NOT NULL,                 -- diag_payload 的 SHA-256(车端幂等校验)
    requires_approval BOOLEAN DEFAULT false,
    approval_id       VARCHAR(64),                       -- 引用 approval_record.approval_id
    status            VARCHAR(32) DEFAULT 'draft',       -- draft/pending_approval/active/paused/completed/expired
    created_by        VARCHAR(64) REFERENCES operator(id),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CHECK (execute_valid_until IS NULL OR valid_until IS NULL OR execute_valid_until >= valid_until)
);

CREATE INDEX idx_task_def_status ON task_definition(status);
CREATE INDEX idx_task_def_priority ON task_definition(priority, created_at);
CREATE INDEX idx_task_def_tenant ON task_definition(tenant_id, status);
CREATE INDEX idx_task_def_supersedes ON task_definition(supersedes_task_id) WHERE supersedes_task_id IS NOT NULL;
```

#### 任务目标车辆关联表 (`task_target`)

```sql
CREATE TABLE task_target (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    target_type       VARCHAR(32) NOT NULL,              -- vin_list/model/tag/all
    target_value      JSONB NOT NULL
);

CREATE INDEX idx_task_target_task ON task_target(task_id);
```

#### 任务分发记录表 (`task_dispatch_record`)

```sql
-- v1.2: ecu_scope 新增(协议 §10.2);dispatch_status 扩充 superseding
CREATE TABLE task_dispatch_record (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    ecu_scope         JSONB,                                -- v1.2: ECU 名数组,如 ["VCU","BMS"]
    dispatch_status   VARCHAR(32) DEFAULT 'pending_online'
        CHECK (dispatch_status IN (
            'pending_online','dispatched','queued','scheduling','executing',
            'paused','deferred','superseding',
            'completed','failed','canceled','expired')),
    dispatched_at     TIMESTAMP,
    completed_at      TIMESTAMP,
    result_payload    JSONB,
    retry_count       INT DEFAULT 0,
    last_error        VARCHAR(128),                         -- 最近一次失败原因
    superseded_by     VARCHAR(64),                          -- 被哪个新 taskId 替换
    current_execution_count INT DEFAULT 0,                  -- v1.2: 云端以 GREATEST(旧, 车端 executionSeq) 更新
    last_reported_at  TIMESTAMP,                            -- 最近一次车端上报
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, vin)
);

CREATE INDEX idx_dispatch_task_vin ON task_dispatch_record(task_id, vin);
CREATE INDEX idx_dispatch_status ON task_dispatch_record(dispatch_status);
CREATE INDEX idx_dispatch_vin_pending ON task_dispatch_record(vin, dispatch_status)
    WHERE dispatch_status = 'pending_online';
CREATE INDEX idx_dispatch_ecu_scope ON task_dispatch_record USING GIN (ecu_scope);
```

#### 任务分发候补表 (`task_dispatch_pending_backlog`)

> [!IMPORTANT]
> 车端 `queue_reject` 时的候补队列,等车端空闲后 replay。详见[协议 §11.8.1](./opendota_protocol_spec.md#1181-云端候补表pending-backlog机制)。

```sql
CREATE TABLE task_dispatch_pending_backlog (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    vin               VARCHAR(17) NOT NULL,
    priority          INT NOT NULL,
    reject_reason     VARCHAR(32) NOT NULL,              -- QUEUE_FULL / SOFT_WATERMARK_LOW_PRIO / ...
    rejected_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    retry_after_at    TIMESTAMP,                         -- 建议下次 replay 时间
    attempt_count     INT DEFAULT 1,
    UNIQUE (task_id, vin)
);
CREATE INDEX idx_backlog_replay ON task_dispatch_pending_backlog(vin, retry_after_at, priority);
```

#### 任务执行日志表 (`task_execution_log`)

```sql
-- v1.2: 新增 begin/end 双 ack 列,支持周期计数一致性(协议 §8.5.1)
CREATE TABLE task_execution_log (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    execution_seq     INT NOT NULL,
    trigger_time      TIMESTAMP NOT NULL,
    overall_status    INT,
    result_payload    JSONB,
    execution_duration INT,
    miss_compensation JSONB,                             -- 若本次为 miss 补偿,携带合并的触发时间列表(协议 §10.7.4)
    begin_reported_at TIMESTAMP,                         -- v1.2: execution_begin 到达时间
    end_reported_at   TIMESTAMP,                         -- v1.2: execution_end 到达时间
    begin_msg_id      VARCHAR(64),                       -- v1.2: execution_begin 报文 msgId
    end_msg_id        VARCHAR(64),                       -- v1.2: execution_end 报文 msgId
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, vin, execution_seq)                 -- 幂等锚点, ON CONFLICT DO NOTHING
);

CREATE INDEX idx_exec_log_task_vin ON task_execution_log(task_id, vin, execution_seq);
CREATE INDEX idx_exec_log_tenant_time ON task_execution_log(tenant_id, created_at DESC);
CREATE INDEX idx_exec_log_pending_end ON task_execution_log(task_id, vin)
    WHERE end_reported_at IS NULL;                       -- 找出 begin 后超时未 end 的执行
```

#### 聚合报文分片表 (`task_result_chunk`) — v1.2

对应协议 [§8.5.2](./opendota_protocol_spec.md#852-聚合报文分片协议)。车端离线累积 `schedule_resp` 超过 200KB 时拆包上报,云端以 `aggregation_id` 重组。

```sql
CREATE TABLE task_result_chunk (
    id                BIGSERIAL PRIMARY KEY,
    aggregation_id    VARCHAR(64) NOT NULL,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    chunk_seq         INT NOT NULL,
    chunk_total       INT NOT NULL,
    payload           JSONB NOT NULL,
    truncated         BOOLEAN DEFAULT FALSE,
    dropped_count     INT DEFAULT 0,
    received_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (aggregation_id, chunk_seq)
);
CREATE INDEX idx_chunk_agg_id ON task_result_chunk(aggregation_id, chunk_seq);
CREATE INDEX idx_chunk_task_vin ON task_result_chunk(task_id, vin, received_at DESC);
```

#### 车端时钟信任表 (`vehicle_clock_trust`) — v1.2

对应协议 [§17](./opendota_protocol_spec.md#17-车端时钟信任模型v12)。车端每 24h 或启动时上报 `time_sync_response`,云端比对后更新此表。

```sql
CREATE TABLE vehicle_clock_trust (
    vin               VARCHAR(17) PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    last_sync_at      TIMESTAMP,
    drift_ms          BIGINT,
    trust_status      VARCHAR(16) DEFAULT 'unknown'
        CHECK (trust_status IN ('trusted','drifting','untrusted','unknown')),
    max_drift_ms      BIGINT DEFAULT 60000,
    last_sync_msg_id  VARCHAR(64),
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_clock_trust_status ON vehicle_clock_trust(trust_status, last_sync_at);
```

#### 通道事件日志表 (`channel_event_log`)

用于 SSE 多源回填(见 [3.2.1](#321-pubsub-广播丢失风险与缓解策略))。

```sql
CREATE TABLE channel_event_log (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    channel_id        VARCHAR(64),
    event             VARCHAR(32) NOT NULL,              -- opened/closed/rejected/idle_timeout/ecu_lost/
                                                          -- vehicle_sleep/doip_*/session_changed/preempted/ready
    current_session   VARCHAR(8),                        -- UDS 会话等级,如 0x03
    current_sec_level VARCHAR(8),
    reason            VARCHAR(64),                       -- 拒绝/抢占原因
    related_task_id   VARCHAR(64),
    operator_id       VARCHAR(64),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_channel_event_vin ON channel_event_log(vin, created_at DESC);
```

#### 条件任务触发日志表 (`condition_fired_log`)

```sql
CREATE TABLE condition_fired_log (
    id                BIGSERIAL PRIMARY KEY,
    task_id           VARCHAR(64) NOT NULL REFERENCES task_definition(task_id),
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    trigger_type      VARCHAR(32) NOT NULL,              -- power_on/signal/dtc/timer/geo_fence
    trigger_snapshot  JSONB NOT NULL,                    -- 命中时的上下文(信号值/DTC/GPS)
    action_taken      VARCHAR(16) NOT NULL,              -- queued/deferred/skipped
    execution_seq     INT,                               -- 关联 task_execution_log.execution_seq
    fired_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cond_fired_task ON condition_fired_log(task_id, fired_at DESC);
CREATE INDEX idx_cond_fired_vin ON condition_fired_log(vin, fired_at DESC);
```

#### 车辆在线状态表 (`vehicle_online_status`)

```sql
CREATE TABLE vehicle_online_status (
    id                BIGSERIAL PRIMARY KEY,
    vin               VARCHAR(17) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    is_online         BOOLEAN DEFAULT false,
    last_online_at    TIMESTAMP,
    last_offline_at   TIMESTAMP,
    mqtt_client_id    VARCHAR(128),
    cert_cn           VARCHAR(64),                       -- mTLS 证书 CN,应等于 vin
    cert_expires_at   TIMESTAMP,                         -- 证书到期时间(用于 SLI 16.1.4 监控)
    agent_version     VARCHAR(32),                       -- 车端 Agent 版本(排查兼容性问题)
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vehicle_online ON vehicle_online_status(is_online);
CREATE INDEX idx_vehicle_cert_expiry ON vehicle_online_status(cert_expires_at)
    WHERE cert_expires_at IS NOT NULL;
```

#### 固件传输会话表 (`flash_session`)

用于支持 `macro_data_transfer` 的断点续传(协议 [§7.5](./opendota_protocol_spec.md#75-macro_data_transfer--数据传输宏))。

```sql
CREATE TABLE flash_session (
    id                BIGSERIAL PRIMARY KEY,
    transfer_session_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    task_id           VARCHAR(64) REFERENCES task_definition(task_id),
    file_sha256       CHAR(64) NOT NULL,
    file_size         BIGINT NOT NULL,
    last_confirmed_offset BIGINT DEFAULT 0,              -- 车端上报的最近一个已确认写入的偏移
    status            VARCHAR(32) DEFAULT 'in_progress', -- in_progress/completed/rolled_back/failed
    target_partition  VARCHAR(8),                        -- A/B
    rollback_on_failure BOOLEAN DEFAULT true,
    approval_id       VARCHAR(64),                       -- 刷写必须走 Maker-Checker
    started_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMP,
    last_heartbeat_at TIMESTAMP                          -- 长时间无心跳 → 告警介入
);
CREATE INDEX idx_flash_session_status ON flash_session(status, last_heartbeat_at);
```

#### SSE 事件流水表 (`sse_event`)

```sql
CREATE TABLE sse_event (
    id                BIGSERIAL PRIMARY KEY,
    tenant_id         VARCHAR(64) NOT NULL,
    vin               VARCHAR(17) NOT NULL,
    event_type        VARCHAR(32) NOT NULL,              -- diag-result/channel-event/task-progress/condition-fired
    source_type       VARCHAR(32) NOT NULL,              -- diag_record/task_execution_log/channel_event_log/...
    source_id         BIGINT NOT NULL,
    payload_summary   JSONB NOT NULL,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_sse_event_vin_id ON sse_event(vin, id);

-- 滚动删除(30 天前的事件),避免表无限增长
SELECT add_retention_policy('sse_event', INTERVAL '30 days');
```

#### 安全审计日志表 (`security_audit_log`)

> [!CAUTION]
> 此表使用**独立 DB 账号 append-only**,业务账号无 DELETE 权限。物理隔离存储账号。
> **v1.2 新增 WORM 加固**(详见协议 [§15.2.1](./opendota_protocol_spec.md#1521-wormwrite-once-read-many与灾备v12)):
> 1. **独立 tablespace**:`CREATE TABLESPACE ts_audit LOCATION '/pgdata/audit'; ALTER TABLE security_audit_log SET TABLESPACE ts_audit;`
> 2. **Append-only trigger**:`BEFORE UPDATE OR DELETE OR TRUNCATE` 触发器抛 EXCEPTION,业务 DB 账号即使被攻破也无法篡改。
> 3. **冷归档 WORM**:> 90 天的数据搬到 S3 Object Lock(Compliance 模式,Retention=3 年),跨区复制 RPO ≤ 5 分钟。
> 4. DBA 启用样板见 `sql/init_opendota.sql` 第 8 节(默认注释,按环境打开)。

```sql
CREATE TABLE security_audit_log (
    id                BIGSERIAL PRIMARY KEY,
    audit_id          VARCHAR(64) NOT NULL UNIQUE,
    msg_id            VARCHAR(64),
    tenant_id         VARCHAR(64) NOT NULL,
    operator_id       VARCHAR(64),
    operator_role     VARCHAR(32),
    ticket_id         VARCHAR(64),
    vin               VARCHAR(17),
    action            VARCHAR(64) NOT NULL,              -- act 值 或 API 动作名
    resource_type     VARCHAR(32),                       -- channel/single/batch/script/schedule/task/queue/firmware
    req_payload       JSONB,                             -- 脱敏后的请求内容
    res_payload       JSONB,
    result            VARCHAR(16) NOT NULL,              -- success/failed/timeout/rejected
    client_ip         VARCHAR(45),
    user_agent        VARCHAR(512),
    chain_id          VARCHAR(64),                       -- 审计链 ID(任务触发时关联创建者的审计 ID)
    timestamp         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_audit_vin_time ON security_audit_log(vin, timestamp DESC);
CREATE INDEX idx_audit_operator_time ON security_audit_log(operator_id, timestamp DESC);
CREATE INDEX idx_audit_tenant_action ON security_audit_log(tenant_id, action, timestamp DESC);

-- 强制保留 3 年(合规要求)
-- 不使用 retention policy,改用冷归档流程
```

### 6.3 行级安全策略 (RLS)

> [!IMPORTANT]
> 多租户隔离必须做到即便 SQL 注入也无法跨租户读取。PG RLS 在所有查询层自动注入 `tenant_id = current_setting('app.tenant_id')` 过滤。

```sql
ALTER TABLE task_definition ENABLE ROW LEVEL SECURITY;
CREATE POLICY rls_task_definition ON task_definition
    USING (tenant_id = current_setting('app.tenant_id', true));

-- 同样应用于 task_dispatch_record, task_execution_log, diag_record, security_audit_log, 
-- vehicle_online_status, flash_session, sse_event, channel_event_log, condition_fired_log
```

Spring Boot 侧通过 `@Transactional` 拦截器,从 JWT 的 `tenantId` 自动设置 `SET LOCAL app.tenant_id = ?`。

### 6.4 TimescaleDB 时序扩展(后期启用)

当诊断日志量达到百万级时,可对 `diag_record` / `task_execution_log` / `sse_event` / `security_audit_log` 启用 TimescaleDB 时序优化:

```sql
CREATE EXTENSION IF NOT EXISTS timescaledb;

SELECT create_hypertable('diag_record', 'created_at');
SELECT create_hypertable('task_execution_log', 'created_at');
SELECT create_hypertable('sse_event', 'created_at');
-- 审计表单独保留策略,不进入热分片

SELECT add_retention_policy('diag_record', INTERVAL '90 days');
SELECT add_retention_policy('task_execution_log', INTERVAL '180 days');
SELECT add_retention_policy('sse_event', INTERVAL '30 days');
```

---

## 7. 前端工程设计

### 7.1 工程结构

```
opendota-web/
├── package.json
├── vite.config.ts
├── tsconfig.json
├── public/
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── api/                          # HTTP API 调用封装
    │   ├── diagApi.ts                # 诊断相关 API
    │   ├── odxApi.ts                 # ODX 服务目录 API
    │   └── vehicleApi.ts            # 车辆管理 API
    ├── hooks/
    │   ├── useSse.ts                 # SSE 订阅 Hook
    │   └── useDiagSession.ts         # 诊断会话状态 Hook
    ├── components/
    │   ├── DiagConsole/              # 诊断控制台（核心组件）
    │   │   ├── ServiceTree.tsx       # 左侧：ECU + 服务目录树
    │   │   ├── CommandPanel.tsx      # 中间：指令发送面板
    │   │   └── ResultTerminal.tsx    # 右侧：结果终端（实时显示）
    │   ├── BatchTaskPanel/           # 批量任务面板
    │   │   ├── ScriptEditor.tsx      # JSON 脚本编辑器
    │   │   └── TaskResultTable.tsx   # 批量结果展示表格
    │   └── common/
    │       ├── HexViewer.tsx         # Hex 数据查看器
    │       └── StatusBadge.tsx       # 状态标签组件
    ├── pages/
    │   ├── DiagPage.tsx              # 单步诊断页面
    │   ├── BatchPage.tsx             # 批量任务页面
    │   ├── OdxManagePage.tsx         # ODX 管理页面
    │   └── VehiclePage.tsx           # 车辆管理页面
    ├── stores/                       # 状态管理 (Zustand 或 Redux)
    │   ├── diagStore.ts
    │   └── vehicleStore.ts
    └── types/
        └── diag.d.ts                 # TypeScript 类型定义
```

### 7.2 核心 SSE Hook

```typescript
// hooks/useSse.ts
import { useEffect, useRef, useCallback } from 'react';

/**
 * SSE 订阅 Hook
 * 用于接收云端推送的诊断结果
 *
 * 断线重连说明：
 * 浏览器原生 EventSource 在连接断开后会自动重连，并在重连请求中携带
 * Last-Event-ID 头（值为最后一次收到的事件 id 字段）。后端据此从 PG 中
 * 查询断线期间的记录并补发（详见 3.2.1 节）。
 *
 * 此处使用手动重连（而非 EventSource 默认重连）是为了支持指数退避策略，
 * 避免服务端故障时大量客户端同时重连导致雪崩。
 */
export function useSse(vin: string, onMessage: (event: string, data: any) => void) {
  const eventSourceRef = useRef<EventSource | null>(null);
  const retryCountRef = useRef(0);
  const maxRetryDelay = 30000; // 最大重连间隔 30 秒

  const connect = useCallback(() => {
    // 关闭已有连接
    eventSourceRef.current?.close();

    const es = new EventSource(`/api/sse/subscribe/${vin}`);

    es.onopen = () => {
      retryCountRef.current = 0; // 连接成功后重置重试计数
    };

    // 监听诊断结果推送
    es.addEventListener('diag-result', (e) => {
      onMessage('diag-result', JSON.parse(e.data));
    });

    // 监听通道状态变更
    es.addEventListener('channel-event', (e) => {
      onMessage('channel-event', JSON.parse(e.data));
    });

    // 监听批量任务结果
    es.addEventListener('batch-result', (e) => {
      onMessage('batch-result', JSON.parse(e.data));
    });

    // 断线自动重连（指数退避 + 抖动）
    // 注意：浏览器 EventSource 重连时会自动携带 Last-Event-ID，
    // 后端据此补发断线期间的消息，无需前端额外处理
    es.onerror = () => {
      es.close();
      const delay = Math.min(1000 * Math.pow(2, retryCountRef.current), maxRetryDelay);
      const jitter = delay * 0.3 * Math.random(); // 30% 随机抖动
      retryCountRef.current++;
      setTimeout(connect, delay + jitter);
    };

    eventSourceRef.current = es;
  }, [vin, onMessage]);

  useEffect(() => {
    if (vin) connect();
    return () => eventSourceRef.current?.close();
  }, [vin, connect]);
}
```

---

## 8. 车端模拟器

### 8.1 定位

在 MVP 阶段，不涉及真实的车端程序开发。但为了端到端验证云端的全链路（下发 → MQTT → 回调 → Redis → SSE → 前端），需要一个**简单的 MQTT 客户端模拟器**来伪装成车端 Agent。

### 8.2 模拟器功能

| 功能 | 说明 |
|:---|:---|
| 订阅 C2V Topic | 监听 `dota/v1/cmd/single/{vin}`、`dota/v1/cmd/batch/{vin}` 等 |
| 解析 Envelope | 解析收到的 JSON，提取 `act` 和 `payload` |
| 模拟 UDS 响应 | 根据 `reqData` 的 Service ID 返回预设的固定响应 |
| 发布 V2C 结果 | 将模拟结果组装为 Envelope 发布到 `dota/v1/resp/single/{vin}` |
| 可配置延迟 | 模拟真实的车端执行耗时（1~3 秒随机延迟） |

### 8.3 预置的模拟响应表

| 请求 reqData 前缀 | 模拟返回的 resData | 说明 |
|:---|:---|:---|
| `22F190` | `62F1904C535657413233...` | 读 VIN：返回 "LSVWA23..." |
| `22F191` | `62F19148575F56312E30` | 读硬件版本号 |
| `22F193` | `62F19342415454` | 读系统供应商 ID |
| `1003` | `500300C80014` | 切换扩展会话：成功 |
| `1001` | `500100C80014` | 切换默认会话：成功 |
| `14FFFFFF` | `54` | 清除所有 DTC：成功 |
| `190209` | `5902090100018F` | 读 DTC：返回 1 个故障码 |
| 其他 | `7F{SID}31` | 默认返回 NRC：RequestOutOfRange |

### 8.4 实现建议

可直接使用 Java 编写一个简易的 Spring Boot CLI 应用。也可以复用你们已有的 `vehicle-mqtt-simulator` 项目，仅需适配本协议的 Envelope 格式即可。

> [!NOTE]
> **DoIP 模拟说明**：MVP 阶段模拟器仅模拟 CAN 路径（`transport=UDS_ON_CAN`）。DoIP 路径的模拟（TCP Server + Routing Activation 响应 + DoIP Header 封装）计划在后续阶段实现。当前阶段若需验证 DoIP 协议字段的下发链路，可通过模拟器忽略 `transport` 字段、直接按 UDS PDU 返回固定响应的方式实现轻量级验证。

---

## 9. 日志与可观测性

### 9.1 日志规范

> [!CAUTION]
> 车云远程诊断涉及直接操作车辆 ECU,所有下发和回传必须留痕。日志是事故追责和问题排查的唯一依据。**SLI/SLO 清单详见[协议规范 §16](./opendota_protocol_spec.md#16-可观测性与-slo)**,此处不重复,只补工程侧埋点和栈路径。

#### MDC 上下文注入

在整个请求链路中,使用 SLF4J MDC 注入关键追踪字段(必须包含):

```java
/**
 * 诊断链路追踪过滤器
 * 从 OTEL traceparent / JWT / 请求头 提取上下文,注入 MDC 贯穿整个请求生命周期
 */
@Component
public class DiagTraceFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest req = (HttpServletRequest) request;
        try {
            // OTEL W3C Trace Context
            String traceId = extractOrGenerateTraceId(req.getHeader("traceparent"));
            MDC.put("traceId", traceId);
            // JWT 里的操作者上下文
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof JwtPrincipal jwt) {
                MDC.put("operatorId", jwt.getSubject());
                MDC.put("tenantId", jwt.getClaim("tenantId"));
                MDC.put("ticketId", Optional.ofNullable(req.getHeader("X-Ticket-Id")).orElse("-"));
            }
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

对 MQTT V2C 处理,从 Envelope 重新恢复 MDC:

```java
public void onMessageArrived(String topic, MqttMessage mqttMessage) {
    DiagMessage<?> env = parseEnvelope(mqttMessage);
    try {
        MDC.put("traceId", env.traceparent() != null ? extractTraceId(env.traceparent()) : "-");
        MDC.put("msgId", env.msgId());
        MDC.put("vin", env.vin());
        if (env.operator() != null) {
            MDC.put("operatorId", env.operator().id());
            MDC.put("tenantId", env.operator().tenantId());
        }
        handleResponse(env);
    } finally {
        MDC.clear();
    }
}
```

#### 关键日志埋点

| 埋点位置 | 日志级别 | 必须包含的字段 | 说明 |
|:---|:---:|:---|:---|
| MQTT Publish 前 | INFO | `msgId`, `vin`, `act`, `reqData`, `operatorId`, `tenantId`, `ticketId` | 下发指令的完整内容 |
| MQTT Subscribe 回调 | INFO | `msgId`, `vin`, `act`, `resData`, `status` | 车端返回的完整内容 |
| ODX 翻译完成 | DEBUG | `msgId`, `translatedResult` | 翻译后的结构化结果 |
| Redis Pub 发送 | DEBUG | `channel`, `msgId`, `sseEventId` | Redis 广播追踪 |
| SSE Push 到前端 | DEBUG | `vin`, `eventType`, `sseEventId` | 前端推送追踪 |
| Kafka Produce / Consume | INFO | `topic`, `partition`, `offset`, `vin` | 分发管道追踪 |
| RBAC 拒绝 | WARN | `operatorId`, `role`, `action`, `vin`, `reason` | 安全审计 |
| 安全违规(CN/VIN 不符、tenant 不符) | ERROR | `topic`, `cert_cn`, `username`, `vin` | 安全告警 |
| force 抢占 | WARN | `channelId`, `preemptingOperatorId`, `preemptedTaskId` | 审计链 |
| 异常/超时 | ERROR | `msgId`, `vin`, `errorMsg`, `stackTrace` | 异常链路还原 |

#### 日志格式

```
# logback-spring.xml 格式
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId}/%X{msgId}/%X{vin}/%X{operatorId}/%X{tenantId}] %-5level %logger{36} - %msg%n
```

### 9.2 Prometheus / Grafana / Jaeger 对接

| 组件 | 部署方式 | 用途 |
|:---|:---|:---|
| Prometheus | K8s Operator 部署,每 15 秒拉取 | 抓取 `/actuator/prometheus` 指标 |
| Grafana | K8s Operator 部署 | 看板:诊断链路、任务分发、资源仲裁、安全告警 |
| Jaeger / Tempo | K8s 部署 | OTEL Traces 采集与查询 |
| Alertmanager | K8s 部署 | 对接即时通讯 / 电话 / 短信渠道 |

Spring Boot 集成:

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    tags:
      application: opendota-server
      environment: ${ENV:dev}
  tracing:
    enabled: true
    sampling:
      probability: 0.1  # 生产环境 10% 采样(高流量场景)
  otlp:
    tracing:
      endpoint: http://otel-collector:4317
```

---

## 10. MVP 开发路线图

### 10.1 阶段划分

```mermaid
gantt
    title OpenDOTA MVP 开发计划
    dateFormat  YYYY-MM-DD
    section Phase 1 - 基础骨架
    搭建 Spring Boot 4 工程骨架      :p1_1, 2026-04-17, 1d
    EMQX 部署与连接测试              :p1_2, after p1_1, 1d
    Redis 部署与 Pub/Sub 验证         :p1_3, after p1_1, 1d
    PostgreSQL 建表                  :p1_4, after p1_1, 1d

    section Phase 2 - 核心链路打通
    MQTT 发布/订阅模块开发           :p2_1, after p1_2, 2d
    车端模拟器开发                    :p2_2, after p2_1, 1d
    SSE 推送模块开发                  :p2_3, after p2_1, 2d
    Redis 异步结果分发开发            :p2_4, after p2_1, 2d
    端到端链路验证                    :p2_5, after p2_4, 1d

    section Phase 3 - ODX 与翻译
    手工注入 ODX 测试数据             :p3_1, after p2_5, 1d
    服务目录查询 API 开发             :p3_2, after p3_1, 2d
    ODX 编码引擎开发                  :p3_3, after p3_2, 2d
    ODX 翻译引擎开发                  :p3_4, after p3_3, 2d

    section Phase 3.5 - 任务管理系统
    任务定义 CRUD API 开发            :p35_1, after p3_2, 2d
    任务分发调度器开发                :p35_2, after p35_1, 2d
    EMQX Webhook 在线状态管理       :p35_3, after p35_1, 1d
    离线任务推送机制开发              :p35_4, after p35_3, 2d
    多 ECU 脚本与队列控制开发          :p35_5, after p35_2, 2d

    section Phase 4 - 前端界面
    React 工程搭建                   :p4_1, after p2_5, 1d
    诊断控制台页面开发                :p4_2, after p4_1, 3d
    批量任务页面开发                  :p4_3, after p4_2, 2d
    任务管理与进度看板页面            :p4_4, after p4_3, 2d

    section Phase 5 - 集成验证
    前后端联调                       :p5_1, after p4_4, 2d
    全链路端到端测试                  :p5_2, after p5_1, 2d
```

### 10.2 Phase 优先级与验收标准

#### Phase 1：基础骨架（Day 1-2）
**目标**：所有基础设施就绪，Spring Boot 能编译启动。
- [ ] Spring Boot 4 + Java 25 工程可编译运行
- [ ] EMQX 单节点部署完成，可通过客户端工具 Pub/Sub
- [ ] Redis 可连接，`PUBLISH/SUBSCRIBE` 命令测试通过
- [ ] PostgreSQL 所有核心表建表完成

#### Phase 2：核心链路（Day 3-6）⭐ 最重要
**目标**：打通"前端发指令 → MQTT 下发 → 模拟车端回复 → Redis 广播 → SSE 推送 → 前端显示"的全异步闭环。
- [ ] 通过 Postman 调用 API 可触发 MQTT 发布
- [ ] 车端模拟器收到消息后 1 秒内自动回复
- [ ] SSE 端点可被浏览器 EventSource 连接
- [ ] 整个闭环走通：下发 `22F190` → 收到 `62F190...` → 推送到浏览器

> [!IMPORTANT]
> Phase 2 是整个 MVP 的**安全网**。只要这条链路走通了，你就可以向任何人演示"远程诊断的核心逻辑"。后续所有的功能（ODX 解析、批量任务、前端美化）都是在这条线上叠加。

#### Phase 3：ODX 与翻译（Day 7-11）
**目标**：原始 Hex 数据可被翻译为人类可读的物理值。
- [ ] 手工注入至少 5 条 ODX 服务定义数据
- [ ] 前端能展示 ECU 服务目录树
- [ ] 下发指令时自动从数据库读取 `requestRawHex`
- [ ] 返回结果可被翻译为中文描述 + 物理值

#### Phase 3.5：任务管理系统（Day 9-14，可与 Phase 3/4 并行）
**目标**：任务 CRUD、分发调度、离线推送、队列控制全流程打通。
- [ ] 任务定义 CRUD API 可用
- [ ] EMQX Webhook 车辆在线状态维护正常
- [ ] 在线车辆任务分发流程打通
- [ ] 离线车辆上线后自动推送待下发任务
- [ ] 多 ECU 编排脚本下发与结果收集
- [ ] 车端任务队列查询与操控流程验证

#### Phase 4：前端界面（Day 7-15，可与 Phase 3/3.5 并行）
**目标**：有一个可用的诊断控制台和任务管理界面。
- [ ] 左侧服务树 + 右侧结果终端的基础布局
- [ ] 点击服务可触发诊断，结果实时显示
- [ ] 批量任务 JSON 编辑与提交
- [ ] 任务创建/列表/进度看板页面

#### Phase 5：集成验证（Day 16-18）
**目标**：全部功能联调通过。
- [ ] 前端全流程可演示
- [ ] 诊断记录可持久化查询
- [ ] 任务分发与离线推送全流程验证
- [ ] 基本的错误处理和超时处理

---

## 11. 开发规约与约定

### 11.1 代码规范

| 规范项 | 约定 |
|:---|:---|
| Java 版本 | 25（必须启用 `--enable-preview` 如有需要） |
| 代码风格 | Google Java Style Guide |
| 注释语言 | **中文**（所有新增代码注释必须使用中文） |
| 包命名 | `com.opendota.{模块名}.{层级}` |
| Record 类 | 优先使用 Java Record 定义不可变 DTO |
| 枚举 | 所有协议相关的常量必须定义为枚举，禁止使用魔法数字和字符串 |

### 11.2 Git 提交规范

```
feat: 新增单步诊断 API 接口
fix: 修复 MQTT 回调中 msgId 匹配失败的问题
refactor: 重构 ODX 翻译引擎的参数解码逻辑
docs: 更新车云协议规范文档
chore: 升级 Spring Boot 至 4.0.1
```

### 11.3 API 设计规范

| 规范项 | 约定 |
|:---|:---|
| 路由风格 | RESTful，资源名使用复数 |
| 请求体 | `application/json` |
| 响应体 | 统一包装为 `{ code, msg, data }` |
| 错误处理 | 使用 `@RestControllerAdvice` 全局异常处理 |
| API 文档 | SpringDoc OpenAPI 3 自动生成，访问路径 `/swagger-ui.html` |

### 11.4 配置文件约定

```yaml
# application.yml 结构约定
spring:
  threads:
    virtual:
      enabled: true               # 启用虚拟线程
  datasource:
    url: jdbc:postgresql://localhost:5432/opendota
    username: opendota
    password: ${DB_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379

# 自定义配置命名空间
opendota:
  mqtt:
    broker-url: tcp://localhost:1883
    client-id: opendota-server-${random.uuid}
    username: ${MQTT_USERNAME}
    password: ${MQTT_PASSWORD}
    topic-prefix: dota/v1
  diag:
    single-timeout-ms: 5000       # 单步诊断默认超时
    channel-idle-timeout-ms: 300000 # 诊断通道空闲超时
```

---

> **文档维护说明**：本文档应随项目迭代持续更新。技术选型变更需经团队评审后同步更新本文档。
