# OpenDOTA REST API 规范

> **版本**: v1.0 (对齐协议 v1.2 / 架构 v1.2)
> **日期**: 2026-04-18
> **状态**: 已定稿,前后端按本文件打桩
> **配套文档**: [协议规范](./opendota_protocol_spec.md) / [技术架构](./opendota_tech_architecture.md) / [车端 Agent 规范](./opendota_vehicle_agent_spec.md)

---

## 目录

1. [通用约定](#1-通用约定)
2. [认证与授权](#2-认证与授权)
3. [错误码映射](#3-错误码映射)
4. [诊断通道与单步/批量指令](#4-诊断通道与单步批量指令)
5. [多 ECU 脚本](#5-多-ecu-脚本)
6. [任务管理](#6-任务管理)
7. [车端队列操控](#7-车端队列操控)
8. [SSE 订阅](#8-sse-订阅)
9. [ODX 服务目录](#9-odx-服务目录)
10. [车辆管理](#10-车辆管理)
11. [操作员与审批流](#11-操作员与审批流)
12. [审计导出](#12-审计导出)
13. [时钟信任查询](#13-时钟信任查询)
14. [固件传输](#14-固件传输)

---

## 1. 通用约定

### 1.1 Base URL

```
生产:  https://api.opendota.{tenant}.com/api/v1
测试:  https://api-staging.opendota.{tenant}.com/api/v1
本地:  http://localhost:8080/api
```

### 1.2 统一响应信封

所有接口响应遵循 `{code, msg, data}` 结构,由 `@RestControllerAdvice` 统一包装:

```json
{
  "code": 0,
  "msg": "success",
  "data": { }
}
```

| `code` | 含义 |
|:------:|:-----|
| `0` | 成功 |
| `40001`~`40099` | 参数校验失败 |
| `40101`~`40199` | 认证失败(缺少 JWT / 过期) |
| `40301`~`40399` | 权限不足 |
| `40401`~`40499` | 资源不存在 |
| `40901` | 资源冲突(如 taskId 重复) |
| `42301` | 车端资源被占用(`Locked`) |
| `42900` | 触发限流 |
| `50001`~`50099` | 服务端内部错误 |
| `50301` | 车端或 Broker 不可用 |
| `50401` | 请求超时 |

具体错误码映射见 §3。

### 1.3 分页与过滤

**分页参数**(Query String):

| 参数 | 类型 | 默认 | 说明 |
|:---|:---:|:---:|:---|
| `page` | int | `1` | 1-based 页码 |
| `size` | int | `20` | 单页大小,上限 `100` |
| `sort` | string | — | 格式 `<field>,<asc\|desc>`,可重复 |

**分页响应**:

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "content": [ /* item[] */ ],
    "page": 1,
    "size": 20,
    "totalPages": 12,
    "totalElements": 235
  }
}
```

### 1.4 必要的请求头

| Header | 是否必填 | 说明 |
|:---|:---:|:---|
| `Authorization` | ✅ | `Bearer <JWT>`(详见 §2) |
| `X-Ticket-Id` | 建议 | 关联工单号,注入到 `operator.ticketId` |
| `X-Idempotency-Key` | POST 建议 | 客户端生成的幂等键(UUID),24h 内同 key 幂等返回 |
| `traceparent` | ❌ | W3C Trace Context,未提供时由网关生成 |
| `Content-Type` | ✅(body 非空) | `application/json; charset=utf-8` |

### 1.5 时间戳格式

所有字段统一使用 **毫秒级 Unix 时间戳** (int64),不接受字符串日期。响应中附加 `_iso` 字段(若有)为 ISO 8601 仅做前端展示。

---

## 2. 认证与授权

### 2.1 JWT Bearer Token

登录(SSO / 账号密码)后拿到 JWT,`Authorization: Bearer <token>`。JWT claims:

| claim | 说明 |
|:---|:---|
| `sub` | `operator.id`,与 `operator` 表主键一致 |
| `tenantId` | 租户 ID,RLS 依此隔离数据 |
| `role` | `viewer` / `engineer` / `senior_engineer` / `admin` |
| `exp` | 过期时间,默认 8h |
| `jti` | JWT ID,用于撤销 |

### 2.2 登录

`POST /auth/login`

```json
{ "email": "zhangsan@chery.example.com", "password": "***", "tenant": "chery-hq" }
```

响应:

```json
{
  "code": 0,
  "data": { "token": "eyJhbGciOi...", "expiresAt": 1713328800000, "operator": { "id": "eng-12345", "displayName": "张三", "role": "engineer", "tenantId": "chery-hq" } }
}
```

### 2.3 刷新

`POST /auth/refresh` — Body 空,Header 带旧 token(未过期),返回新 token。

### 2.4 RBAC 矩阵

细粒度权限依 [协议 §15.4.2](./opendota_protocol_spec.md#1542-操作权限表)。后端实现样板:

```java
@PreAuthorize("@rbac.canExecute('channel_open', authentication, #req.vin, #req.force)")
```

权限拒绝时返回 `code=40301`,`data.reason` 说明具体拦截的动作。

---

## 3. 错误码映射

> 业务错误码三段式:`域码(2 位) + HTTP 对齐(3 位) + 递增序号(2 位)` = **7 位**,如 `40001`。

### 3.1 通用

| code | HTTP | 含义 |
|:---:|:---:|:---|
| `0` | 200 | 成功 |
| `40001` | 400 | 参数缺失 |
| `40002` | 400 | 参数格式错误 |
| `40003` | 400 | 参数超出允许范围 |
| `40101` | 401 | 未登录 |
| `40102` | 401 | Token 过期 |
| `40103` | 401 | Token 被撤销 |
| `40301` | 403 | RBAC 权限不足 |
| `40302` | 403 | 需二次审批(Maker-Checker) |
| `40401` | 404 | 资源不存在 |
| `40901` | 409 | 资源重复(如 taskId 已存在) |
| `42301` | 423 | 车端资源被占用 |
| `42900` | 429 | 触发限流 |
| `50001` | 500 | 内部错误 |
| `50301` | 503 | 下游不可用(MQTT/Kafka/PG) |
| `50401` | 504 | 下发超时 |

### 3.2 任务域

| code | 含义 |
|:---:|:---|
| `40305` | **v1.4 A2** 不可中断宏(`macro_data_transfer` / `macro_security` 半程)不允许 force_cancel,即便 admin 角色 |
| `41001` | `maxExecutions=-1` 且 `executeValidUntil` 未设置(防永动机,见 R3) |
| `41002` | `ecuScope` 必填缺失 |
| `41003` | `ecuScope` 中 ECU 不存在于车型 ODX |
| `41004` | `scheduleType` 与 `scheduleConfig` 字段不匹配 |
| `41005` | `payloadHash` 与 `diagPayload` 计算值不符 |
| `41006` | 条件任务 `signalCatalogVersion` 缺失或落后 |
| `41007` | `targetScope` 解析出的目标车辆数为 0 |
| `41008` | `supersedes` 指定的旧任务不存在或不在同一租户 |
| `41010` | 任务执行窗口 `executeValidUntil < validUntil`(协议 §8.2.5 约束) |
| `41011` | **v1.4 A6** `executeValidFrom < validFrom`(协议 §8.2.5) |
| `41012` | **v1.4 A3** `targetScope.mode='dynamic'` 但 `scheduleType` 非 periodic/conditional |

### 3.3 车端反馈

| code | 含义 |
|:---:|:---|
| `42301` | 车端 `channel_event rejected:TASK_IN_PROGRESS`(对应 HTTP 423) |
| `42302` | 车端 `queue_reject:QUEUE_FULL` |
| `42303` | 车端 `queue_reject:SIGNAL_CATALOG_STALE`(白名单版本落后) |
| `42304` | 车端 `channel_event rejected:ECU_ALREADY_OCCUPIED` |
| `42305` | 车端 `channel_event rejected:NON_PREEMPTIBLE_TASK` |
| `42306` | 车端 `clock_untrusted`,定时任务被挂起 |

---

## 4. 诊断通道与单步/批量指令

### 4.1 开启通道

`POST /diag/channel/open`

**Body**:

```json
{
  "vin": "LSVWA234567890123",
  "ecuName": "VCU",
  "ecuScope": ["VCU"],
  "transport": "UDS_ON_CAN",
  "txId": "0x7E0",
  "rxId": "0x7E8",
  "globalTimeoutMs": 300000,
  "force": false,
  "preemptPolicy": "wait",
  "doipConfig": null,
  "workstationId": null
}
```

**响应**:

```json
{ "code": 0, "data": { "msgId": "550e8400-...", "channelId": "ch-uuid-001" } }
```

- 仅下发 MQTT,立即返回(协议 §3.1 全异步)
- 实际开通结果通过 SSE `channel-event` 推送
- `force=true` 必须 `role>=senior_engineer`,否则 `code=40301`
- `ecuScope` 必填,单 ECU 场景等于 `[ecuName]`(R2 约束)

### 4.1.1 ECU 作用域推导(`GET /vehicle/{vin}/ecu-scope`)

人工开通道场景下,前端无法预知"读 BMS 是否需要同时锁 GW 网关"。此接口由 ODX 引擎基于 `odx_ecu.gateway_chain`(JSONB,记录 ECU 的网关依赖链)自动返回完整 `ecuScope`。

`GET /vehicle/{vin}/ecu-scope?ecuName=BMS`

**响应**:

```json
{
  "code": 0,
  "data": {
    "vin": "LSVWA234567890123",
    "modelCode": "CHERY_EXEED",
    "requested": "BMS",
    "ecuScope": ["BMS", "GW"],
    "reason": "BMS 位于 OBD2 总线后,通过 GW 网关路由;锁 BMS 必须同时锁 GW"
  }
}
```

**前端约定**:打开通道向导页面时自动调用本接口,把返回的 `ecuScope` 作为默认值填入表单,用户仅看到只读提示"本次操作将锁定 {BMS, GW} 两个 ECU"。若调用失败,前端传 `ecuScope: [ecuName]` 并提示用户"无法推导依赖,仅锁定目标 ECU"。

**批量推导**:`POST /vehicle/{vin}/ecu-scope/resolve` Body `{"ecuNames":["BMS","EPS"]}`,返回并集后的 `ecuScope`,用于多 ECU 脚本场景。

### 4.2 关闭通道

`POST /diag/channel/{channelId}/close`

```json
{ "resetSession": true }
```

### 4.2.1 取消 pending 通道(v1.4 A5)

`POST /diag/channel/pending/{channelId}/cancel`

**背景**:当 `POST /diag/channel/open` 的 `preemptPolicy="wait"` 被车端拒绝时,云端把请求写入 `diag_channel_pending`(架构 §4.3.2)等待 `channel_ready` 自动 replay,默认超时 3 分钟。若用户已不想等,必须主动撤销 —— 否则任务自然超时前 3 分钟内任何 `channel_ready` 都会误触发 replay,打开一个用户已不关注的通道。

**Body**:

```json
{ "reason": "user_closed_tab" }
```

| 字段 | 说明 |
|:---|:---|
| `reason` | `user_closed_tab`(默认) / `replaced_by_new_request` / `manual_cancel` |

**响应**:

```json
{ "code": 0, "data": { "channelId": "ch-uuid-001", "previousStatus": "waiting", "canceledAt": 1713300123000 } }
```

**错误码**:

| code | 含义 |
|:---|:---|
| `40401` | channelId 不存在或已超时/已 replay |
| `40301` | RBAC 校验失败(通道不属于当前 operator) |

**前端约定**:React `useSse` 订阅 `channel-rejected` 后启动倒计时;用户关闭弹窗/切页面前必须调用本 API。`useEffect` cleanup 里默认调用。

### 4.3 单步指令

`POST /diag/cmd/single`

```json
{
  "channelId": "ch-uuid-001",
  "type": "raw_uds",
  "reqData": "22F190",
  "timeoutMs": 5000
}
```

**响应**:`{ code: 0, data: { msgId } }`,结果走 SSE。

### 4.4 批量任务

`POST /diag/cmd/batch`

```json
{
  "vin": "LSVWA234567890123",
  "priority": 3,
  "ecuName": "VCU",
  "ecuScope": ["VCU"],
  "transport": "UDS_ON_CAN",
  "txId": "0x7E0",
  "rxId": "0x7E8",
  "strategy": 1,
  "steps": [
    { "seqId": 1, "type": "raw_uds", "data": "1003", "timeoutMs": 2000 },
    { "seqId": 2, "type": "macro_security", "level": "01", "algoId": "algo_vcu_1" }
  ]
}
```

---

## 5. 多 ECU 脚本

`POST /diag/cmd/script`

```json
{
  "vin": "LSVWA234567890123",
  "priority": 3,
  "executionMode": "parallel",
  "globalTimeoutMs": 60000,
  "ecus": [
    {
      "ecuName": "VCU",
      "ecuScope": ["VCU"],
      "transport": "UDS_ON_CAN",
      "txId": "0x7E0",
      "rxId": "0x7E8",
      "strategy": 1,
      "steps": [ { "seqId": 1, "type": "raw_uds", "data": "190209", "timeoutMs": 3000 } ]
    },
    {
      "ecuName": "BMS",
      "ecuScope": ["BMS"],
      "transport": "UDS_ON_CAN",
      "txId": "0x7E3",
      "rxId": "0x7EB",
      "strategy": 1,
      "steps": [ { "seqId": 1, "type": "raw_uds", "data": "190209", "timeoutMs": 3000 } ]
    }
  ]
}
```

**后端行为**:
- 聚合 `ecus[].ecuName` 生成总的 `ecuScope`(按字典序去重),透传给车端
- 同一 `scriptId` 下所有 ECU 结果通过单条 `script_resp` 合并上报
- 结果通过 SSE `script-result` 推送

---

## 6. 任务管理

### 6.1 创建任务

`POST /task`

```json
{
  "taskName": "全车型 BMS 周期 DTC 扫描",
  "priority": 5,
  "validFrom": 1713300000000,
  "validUntil": 1713904800000,
  "executeValidFrom": 1713300000000,
  "executeValidUntil": 1714509600000,
  "scheduleType": "periodic",
  "scheduleConfig": {
    "mode": "periodic",
    "cronExpression": "0 */5 * * * ?",
    "maxExecutions": -1,
    "validWindow": { "startTime": 1713300000000, "endTime": 1714509600000 },
    "vehicleState": { "ignition": "ON", "speedMax": 120, "gear": "ANY" }
  },
  "missPolicy": null,
  "payloadType": "batch",
  "diagPayload": {
    "ecuName": "BMS",
    "ecuScope": ["BMS"],
    "transport": "UDS_ON_CAN",
    "txId": "0x7E3",
    "rxId": "0x7EB",
    "strategy": 1,
    "steps": [ { "seqId": 1, "type": "raw_uds", "data": "190209", "timeoutMs": 3000 } ]
  },
  "targetScope": {
    "type": "model",
    "mode": "dynamic",
    "value": { "modelCode": "CHERY_EXEED" }
  },
  "requiresApproval": false
}
```

**`targetScope.mode` 字段**(v1.4 A3):

| 值 | 语义 | 适用 |
|:---|:---|:---|
| `snapshot`(默认) | 创建时解析一次,冻结目标集合;之后新下线的 VIN **不**自动纳入 | 一次性单次/定时任务(`once`/`timed`) |
| `dynamic` | 后台 `DynamicTargetScopeWorker` 每 1 小时扫描匹配条件的新 VIN,自动补发 `task_dispatch_record`;直到任务 `status=completed/expired/canceled` 停止 | 周期/条件任务(`periodic`/`conditional`)。**仅**这两种 `scheduleType` 允许设置 `dynamic`,否则 `code=41012` |

**示例:奇瑞星途瑶光全车型周期 DTC 扫描,期望覆盖未来 6 个月内投产的所有新车**
```
"scheduleType": "periodic",
"targetScope": { "type": "model", "mode": "dynamic", "value": { "modelCode": "CHERY_EXEED" } },
"validUntil": <6 个月后>
```

**后端校验规则**(R1/R2/R3 相关):

| 规则 | 失败 code |
|:---|:---:|
| `maxExecutions != -1 OR executeValidUntil IS NOT NULL` | `41001` |
| `diagPayload.ecuScope` 非空 | `41002` |
| `executeValidUntil >= validUntil`(协议 §8.2.5) | `41010` |
| `executeValidFrom >= validFrom`(协议 §8.2.5 v1.4) | `41011` |
| `targetScope.mode='dynamic'` 仅限 `scheduleType IN (periodic, conditional)` | `41012` |
| `scheduleType=conditional` 时必须带 `scheduleConfig.signalCatalogVersion` | `41006` |
| `targetScope` 解析出目标 >0 辆车 | `41007` |
| `missPolicy` 未传时按协议 §8.3.4 自动推导(DTC→fire_all,其它→fire_once),**由 API 层在持久化前写入** | — |
| `payloadHash = SHA-256(canonicalized(diagPayload))` 由 API 层计算,不接受客户端传值 | — |
| `executeValidFrom` 未传时默认等于 `validFrom`(协议 §8.2.5 v1.4 A6) | — |

**响应**:

```json
{ "code": 0, "data": { "taskId": "tsk_abc123", "status": "active", "totalTargets": 1250, "dispatchMode": "batch_eager" } }
```

**`dispatchMode`**(v1.4 A1):

| 值 | 触发条件 | 下发路径 & SLO |
|:---|:---|:---|
| `single_immediate` | `totalTargets ≤ 100` | 在线车走 §4.4.1 立即下发,**不**经过 §13.9 jitter 分桶;P95 < 10s(`dota_online_dispatch_latency_seconds`) |
| `batch_eager` | `100 < totalTargets ≤ 10000` | 在线车仍立即下发,仅对 `pending_online → connected` 后的 replay 走 jitter 分桶;P95 < 30s(`dota_batch_dispatch_latency_seconds`) |
| `batch_throttled` | `totalTargets > 10000` | 一律通过 `task-dispatch-queue` 削峰,四级限流 + 分桶 jitter;P95 < 60s(`dota_batch_dispatch_latency_seconds`) |

> [!IMPORTANT]
> v1.4 决议(A1/B2):**惊群防护 jitter 仅作用于"离线 → 上线"的 replay 路径,不影响在线车的首次任务下发**。这是 `dota_offline_task_push_latency P95 < 10s` 与 `jitterMaxMs=30000` 并存的底层前提。对应架构 §4.4.4 的 `TaskDispatchScheduler.scheduleReplay()` 在实现时按调用方区分是否应用 jitter。

### 6.2 任务修订(supersedes)

`POST /task/{taskId}/revise`

```json
{ "scheduleConfig": { /* 修改后的 schedule */ }, "priority": 2 }
```

**后端动作**:
- 创建新 `task_definition`,`version = old.version + 1`,`supersedes_task_id = old.taskId`
- 对每辆已分发的车重发 `schedule_set` 带 `supersedes` 字段
- 旧任务 `task_dispatch_record.dispatch_status = superseding`,车端 ack 后落 `canceled reason=SUPERSEDED`

### 6.3 任务 CRUD

| 方法 | 路径 | 说明 |
|:---|:---|:---|
| `GET` | `/task/{taskId}` | 查单个任务详情 |
| `GET` | `/task` | 分页列表,支持 `status`/`priority`/`scheduleType`/`createdBy` 过滤 |
| `PUT` | `/task/{taskId}/pause` | 暂停(所有分发记录转 `paused`,下发 `task_pause`) |
| `PUT` | `/task/{taskId}/resume` | 恢复 |
| `DELETE` | `/task/{taskId}` | 取消(`task_cancel` 广播 + 标记 `canceled`) |
| `GET` | `/task/{taskId}/versions` | 版本链(supersedes 追溯) |

### 6.4 任务进度聚合

`GET /task/{taskId}/progress`

```json
{
  "code": 0,
  "data": {
    "taskId": "tsk_abc123",
    "taskName": "全车型 DTC 扫描",
    "version": 2,
    "supersedesTaskId": "tsk_abc000",
    "status": "active",
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
    "completionRate": 0.54,
    "boardStatus": "in_progress"
  }
}
```

- `boardStatus` 取自 `v_task_board_progress` 视图(架构 §4.8.1)
- 11 个状态值对齐协议 §14.4.3

### 6.5 任务执行日志

`GET /task/{taskId}/executions?vin=LSVWA234567890123&page=1&size=50`

```json
{
  "code": 0,
  "data": {
    "content": [
      {
        "executionSeq": 7,
        "vin": "LSVWA234567890123",
        "triggerTime": 1713300300000,
        "overallStatus": 0,
        "executionDuration": 5200,
        "beginReportedAt": 1713300300120,
        "endReportedAt": 1713300305320,
        "missCompensation": null,
        "resultPayload": { /* 省略 */ }
      }
    ],
    "page": 1, "size": 50, "totalPages": 3, "totalElements": 142
  }
}
```

---

## 7. 车端队列操控

### 7.1 查询车端队列

`GET /vehicle/{vin}/queue`

**查询参数**:

| 参数 | 类型 | 默认 | 说明 |
|:---|:---|:---|:---|
| `freshnessMs` | int | `5000` | 缓存有效期毫秒。若 `lastReportedAt` 在 `now - freshnessMs` 之内则直接返回缓存;否则发 `queue_query` MQTT 向车端主动拉取 |
| `wait` | boolean | `false` | `true`:等 MQTT `queue_status` 回来后再返回(最长 `waitTimeoutMs`);`false`:立即返回缓存,车端响应后走 SSE `queue-status` 事件 |
| `waitTimeoutMs` | int | `3000` | `wait=true` 时的等待上限,超时返回缓存并带 `staleWarning=true` |

**响应**(`wait=false` 异步模式,v1.4 A4):

```json
{
  "code": 0,
  "data": {
    "source": "cached",
    "queryIssued": true,
    "issuedMsgId": "q-550e8400-...",
    "lastReportedAt": 1713300000000,
    "lastReportedSource": "vehicle_push",
    "staleMs": 8200,
    "queueSize": 3,
    "currentExecuting": "task-001",
    "resourceState": "TASK_RUNNING",
    "resourceStates": { "VCU": "DIAG_SESSION", "BMS": "TASK_RUNNING", "EPS": "IDLE" },
    "tasks": [
      { "taskId": "task-001", "priority": 1, "status": "executing", "ecuScope": ["BMS"] }
    ]
  }
}
```

| 字段 | 说明 |
|:---|:---|
| `source` | `cached` 返回的是 vehicle_queue_cache;`fresh` 刚刚收到最新回执 |
| `queryIssued` | 本次是否发了 MQTT `queue_query`(缓存过期时 true) |
| `issuedMsgId` | 发出的 query 报文 msgId,前端可用此订阅 SSE 精确匹配 |
| `lastReportedAt` | 缓存最后更新时刻(车端主动推送或上次 query 响应) |
| `lastReportedSource` | `vehicle_push`(车端主动) / `cloud_query`(云端 pull) |
| `staleMs` | `now - lastReportedAt`,前端可据此显示"数据 X 秒前" |

**前端约定**:
- **默认模式**(`wait=false`):立即返回缓存 + 订阅 SSE `queue-status` 更新,零阻塞
- **同步模式**(`wait=true`):用于用户点击"刷新"按钮,最多等 3 秒显示 loading 态
- **不要反复轮询**本接口:缓存过期会重复发 `queue_query`,应依赖 SSE 推送。建议手动刷新间隔 ≥ 10 秒,由前端 debounce

**SSE 事件 `queue-status`** 格式(配合本接口):

```
event: queue-status
id: 87654
data: { "vin": "LSV...", "lastReportedAt": 1713300008200, "queueSize": 2, "tasks": [...] }
```

每次车端 `queue_status` 上报都触发 SSE。前端拿到后覆盖本地状态即可,无需再 GET。

### 7.2 删除/暂停/恢复

```
POST /vehicle/{vin}/queue/{taskId}/delete
POST /vehicle/{vin}/queue/{taskId}/pause
POST /vehicle/{vin}/queue/{taskId}/resume
```

均返回 `{ code: 0, data: { msgId } }`,实际结果通过 SSE `queue-status` 推送。

---

## 8. SSE 订阅

### 8.1 端点

`GET /sse/subscribe/{vin}`

**响应**:`text/event-stream`,永不超时(由前端断线重连)。

### 8.2 事件类型

| event | 数据源 | 说明 |
|:---|:---|:---|
| `diag-result` | `diag_record` | 单步诊断结果(含翻译) |
| `batch-result` | `task_execution_log` | 批量任务完成 |
| `script-result` | `task_execution_log` | 脚本任务完成 |
| `channel-event` | `channel_event_log` | 通道开关/拒绝/抢占/会话变更 |
| `task-progress` | `task_dispatch_record` | 任务分发状态跃迁 |
| `queue-status` | MQTT `queue_status` 直推 | 队列状态变更 |
| `condition-fired` | `condition_fired_log` | 条件任务触发命中 |
| `clock-trust` | `vehicle_clock_trust` | 时钟信任状态降级 |
| `replay-truncated` | — | 断线补发超 1000 条,提示前端整页刷新 |

### 8.3 Last-Event-ID 重连契约

**浏览器原生 EventSource 自动携带** `Last-Event-ID` 头,后端 `WHERE vin=? AND id > ?` 从 `sse_event` 表查询补发,上限 1000 条。

> [!IMPORTANT]
> 前端**不要**手动 `close()` + `new EventSource()` 重连 —— 这会丢失 Last-Event-ID。正确做法是让浏览器原生重连(默认 3s 退避),或使用自定义退避时保留 lastId 拼成 URL 查询参数回传。详见 [架构 §7.2](./opendota_tech_architecture.md#72-核心-sse-hook)。

---

## 9. ODX 服务目录

### 9.1 车型 / ECU / 服务 级联查询

```
GET /odx/models                          // 车型列表
GET /odx/models/{modelId}/ecus           // ECU 列表
GET /odx/ecus/{ecuId}/services           // 按 category 分组的服务目录(协议 §13.4.2)
GET /odx/services/{serviceId}            // 单服务详情 + 参数编解码
```

### 9.2 ODX 导入

`POST /admin/odx/import` (`admin` 角色)

`multipart/form-data`:`file=<*.pdx>`, `modelCode`, `odxVersion`。异步处理,响应返回 `importJobId`,通过 `GET /admin/odx/import/{importJobId}` 轮询状态。

### 9.3 信号白名单(条件任务依赖)

```
GET  /odx/models/{modelId}/signal-catalog                  // 当前启用版本
GET  /odx/models/{modelId}/signal-catalog/versions         // 版本列表
POST /admin/odx/models/{modelId}/signal-catalog/publish    // 发布新版本(admin)
```

发布新版本触发 `signal_catalog_push` MQTT 下发(协议 R4,见 §8.3.2)。

---

## 10. 车辆管理

```
GET    /vehicle/{vin}                              // 单车详情 (在线状态 / cert / agent 版本 / 时钟信任)
GET    /vehicle?tenantId=...&onlineOnly=true       // 分页列表
POST   /vehicle                                    // 注册新车(admin)
PUT    /vehicle/{vin}/cert                         // 证书续期(admin,挂审批)
DELETE /vehicle/{vin}                              // 注销(admin,挂审批)
```

---

## 11. 操作员与审批流

### 11.1 操作员

```
GET  /operator/me                                  // 当前用户信息
GET  /admin/operator?tenantId=...                  // 列表 (admin)
POST /admin/operator                               // 创建 (admin)
PUT  /admin/operator/{id}/disable                  // 停用 (admin,触发任务归属迁移,见 R10)
```

**R10 下线副作用**:停用操作员时,其创建的所有 `active`/`periodic`/`conditional` 任务触发时 `operator` 字段 fallback 到 `system(tenantId)`,`ticketId` 保留原值;审计日志 `chain_id` 指向原创建者的 `audit_id` 以保持溯源链。

### 11.2 审批流(Maker-Checker)

```
POST /approval                                     // 发起审批
GET  /approval?status=pending&forMe=true           // 待我批
POST /approval/{id}/approve                        // 批准
POST /approval/{id}/reject                         // 拒绝
```

**发起请求**:

```json
{
  "action": "firmware_flash",
  "resourceRef": "tsk_ota_001",
  "expiresAt": 1713386400000,
  "note": "VCU v2.3.0 紧急修复包"
}
```

约束:`requested_by != approved_by`(SQL CHECK),24h 默认有效期。

---

## 12. 审计导出

`POST /admin/audit/export` (`admin` only)

```json
{
  "startTime": 1713300000000,
  "endTime": 1713386400000,
  "operatorId": null,
  "vin": null,
  "action": null,
  "format": "csv"
}
```

响应返回 `downloadUrl`(带预签名,15 分钟有效期),后台异步生成,完成时发 SSE `audit-export-ready`。

---

## 13. 时钟信任查询

```
GET /vehicle/{vin}/clock-trust
```

```json
{
  "code": 0,
  "data": {
    "vin": "LSVWA234567890123",
    "trustStatus": "trusted",
    "driftMs": 230,
    "lastSyncAt": 1713300000000,
    "maxDriftMs": 60000,
    "rtcSource": "gps_synced"
  }
}
```

`trustStatus=untrusted` 时,前端应禁用创建 `timed` 任务的入口并提示"车辆时钟不可信,定时任务将被挂起"。

---

## 14. 固件传输

```
POST /admin/firmware                               // 上传(admin,先传 OSS)
GET  /admin/firmware                               // 列表
POST /task/{taskId}/firmware-url/{firmwareId}      // 生成预签名 URL(15 分钟有效期)
```

OTA 任务创建流程(见协议 §15.3):
1. `POST /admin/firmware` 上传,生成 `fileSha256`
2. `POST /approval` 申请 `action=firmware_flash`
3. 审批通过后 `POST /task` 创建 OTA 任务(`requiresApproval=true`, `approvalId=xxx`, `payloadType=batch`, `diagPayload.steps=[{type:macro_data_transfer, ...}]`)
4. API 层在下发瞬间调 `POST /task/{taskId}/firmware-url` 生成预签名 URL 注入 payload

---

## 附录 A:SpringDoc 与 OpenAPI 产物

- `GET /v3/api-docs` — OpenAPI 3 JSON
- `GET /swagger-ui.html` — 交互式文档
- `opendota-web` 前端构建时从 `/v3/api-docs` 拉取自动生成 TypeScript client(通过 `openapi-typescript-codegen`),避免手写类型漂移

## 附录 B:本文件未覆盖的待补

- WebSocket(非 SSE)接口 —— v1.0 无此需求
- GraphQL 端点 —— v1.0 不做
- 跨租户全局管理端 —— 平台级,独立 superadmin 域,另起规范
