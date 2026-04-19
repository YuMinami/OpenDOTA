# payloadHash 规范化算法(Canonicalization)

> **权威来源**。协议 §8.2 `schedule_set.payload_hash`、REST §6.1 `POST /task.payloadHash`、车端规范 §3.1 `task.payload_hash`、SQL `task_definition.payload_hash` / `task.payload_hash`(SQLite) 均引用本文件。

## 1. 目的

保证**云端 Java 编码器**、**车端 Rust Agent**、**可能的第三方集成**对同一个 `diagPayload` 对象计算出的 SHA-256 哈希**逐字节相等**,是 `code=41005 payload_hash_mismatch` 检测和端到端任务不可变性保障的唯一锚点。

## 2. 算法选型

采用 **RFC 8785 — JSON Canonicalization Scheme (JCS)**。

- JCS 是 IETF 标准,已被 W3C VC、FIDO2、DID 等广泛采用,有跨语言参考实现。
- 相比 "Jackson sorted" 方案,JCS 对数字精度(IEEE 754)、Unicode 规范化(NFC)、转义字符有严格规定,Rust 车端的 `serde_json` + `rfc8785-rs` 与 Java 的 `erdtman/java-json-canonicalization` 产出字节相同。
- 最终 hash = `SHA-256(JCS(diagPayload))` 的 **小写 hex 字符串**(64 字符)。

## 3. 规范化步骤

对 `diagPayload` JSON 对象依次执行:

| # | 规则 | 说明 |
|:-:|:-----|:-----|
| 1 | 字段按 UTF-16 码点**字典序升序** | RFC 8785 §3.2.3,不是 UTF-8 码点 |
| 2 | 对象 key 的字符串按 UTF-8 转义,控制字符 `\u00XX` | `\b \f \n \r \t \" \\` 使用短转义,其他 < 0x20 用 `\u` |
| 3 | 字符串值做 Unicode NFC 规范化 | 避免 `é`(U+00E9)与 `é`(U+0065 U+0301)哈希不同 |
| 4 | 数字按 IEEE 754 double 序列化 | 整数值 `120` 不得输出 `120.0`;小数用最短十进制表示(ECMA-262 §6.1.6.1.13) |
| 5 | 布尔/null 原样输出 `true`/`false`/`null` | |
| 6 | 对象内字段间用 `,` 无空白分隔,`:` 无空白 | 输出样式: `{"a":1,"b":2}` |
| 7 | 数组保持**出现顺序**,不重排序 | 语义性顺序(`steps[n].seqId`)必须稳定 |
| 8 | **出于 OpenDOTA 业务约束追加的预处理**(在 JCS 之前执行) | 见 §4 |

## 4. OpenDOTA 专属预处理(JCS 之前)

RFC 8785 对**值的业务语义**不作要求,为避免开发中因大小写/默认值差异产生的 mismatch,JCS 之前先做以下归一化:

### 4.1 Hex 字符串小写

所有 `txId` / `rxId` / `Step.data` / `activationType` / `reqData` 等符合 `/^0[xX][0-9a-fA-F]+$/` 的字段,强制转成小写(`0x7E0` → `0x7e0`,`190209` → `190209`)。schema 校验已要求 lowercase,这一步是兜底。

### 4.2 `ecuScope` 数组排序

`ecuScope` / `ecus[n].ecuScope` 两处**必须**按 UTF-16 字典序升序排序后再参与 hash。这是 RFC 8785 §7 允许的"业务应用级规范化",因为集合语义与顺序无关,但 JCS 本身不重排数组。

### 4.3 默认值显式展开

以下带默认值的字段,如果客户端提交时省略,API 层**必须在持久化前按 schema 的 `default` 显式填充**,再参与 hash:

| 字段 | 默认值 |
|:-----|:-------|
| `transport` | `"UDS_ON_CAN"` |
| `doipConfig.ecuPort` | `13400` |
| `doipConfig.activationType` | `"0x00"` |
| `strategy` | `1` |
| `Step.timeoutMs` | `3000` |
| `ScriptDiagPayload.priority` | `5` |

> 不允许省略:客户端传入的 payload 与 DB 存储的 payload **必须逐字段一致**。拉取 `GET /task/{taskId}` 返回的 payload 也必须是展开后的形态,否则 revise 场景重算 hash 会失败。

### 4.4 `null` vs 省略

对可选字段:
- **显式传入 `null`** → 保留 `null` 参与 hash
- **省略** → schema 不要求的字段直接不进 canonical 输出
- 两者结果不同。REST 层约定:对文档中标注"❌ 可选"的字段,客户端**二选一**,前端 codegen 生成的 TypeScript client 固定使用省略模式。

### 4.5 空对象/空数组

`{}` 和 `[]` 参与 hash;不视同 `null`。

## 5. 参考实现

### 5.1 Java(opendota-common)

```java
// pom: <dependency><groupId>io.github.erdtman</groupId><artifactId>java-json-canonicalization</artifactId><version>1.1</version></dependency>
public String payloadHash(JsonNode diagPayload) {
    JsonNode preprocessed = DiagPayloadPreprocessor.normalize(diagPayload); // §4
    String canonical = JsonCanonicalizer.of(preprocessed.toString()).getEncodedString();
    byte[] sha256 = MessageDigest.getInstance("SHA-256")
        .digest(canonical.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(sha256); // 小写 64 位 hex
}
```

### 5.2 Rust(车端 Agent)

```toml
# Cargo.toml
serde_json = "1"
rfc8785 = "0.2"
sha2 = "0.10"
```

```rust
pub fn payload_hash(diag_payload: &serde_json::Value) -> String {
    let preprocessed = preprocess_normalize(diag_payload); // §4
    let canonical = rfc8785::to_vec(&preprocessed).unwrap();
    let digest = sha2::Sha256::digest(&canonical);
    hex::encode(digest) // 小写
}
```

## 6. 校验时机

| 位置 | 时机 | 失败行为 |
|:-----|:-----|:--------|
| `POST /task` | API 层在持久化前计算,写入 `task_definition.payload_hash` | 不接受客户端传入的 hash |
| `schedule_set` 下发 | Envelope 构建时从 DB 读取并携带 | — |
| 车端 Agent 收到 `schedule_set` | 对 `diagPayload` 重算 hash | 不匹配时 `task_ack.status = "hash_mismatch"`,拒绝入队,云端收到后回滚 `dispatch_status = failed (last_error = PAYLOAD_HASH_MISMATCH)` |
| `task/revise` (supersedes) | 新 payload 重算 hash,允许与旧不同;车端 ack 前后用不同 hash 校验 | 同上 |

## 7. 测试向量(conformance)

为协议附录 D §D.2 新增 5 条测试用例:

| TV-Hash-01 | 最小 batch payload,仅必填字段 | 期望 hex |
|:-----------|:------------------------------|:---------|
| TV-Hash-02 | 同 01,但客户端省略 `transport`(默认 `UDS_ON_CAN`);hash 必须等于 TV-Hash-01 | 相等 |
| TV-Hash-03 | `ecuScope` 传入 `["BMS","VCU"]` 与 `["VCU","BMS"]`,hash 必须相等 | 相等 |
| TV-Hash-04 | Hex 字符串大小写混用(`0x7E0` vs `0x7e0`),hash 必须相等 | 相等 |
| TV-Hash-05 | 数字 `120` 与 `120.0`,hash 必须相等 | 相等 |

实际期望 hex 值由 JCS 参考实现生成,固化在 `doc/schema/test_vectors/payload_hash.json`,车端 Rust 单测与云端 Java 单测共用。
