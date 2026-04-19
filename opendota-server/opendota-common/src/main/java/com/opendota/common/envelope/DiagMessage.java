package com.opendota.common.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;
import java.util.UUID;

/**
 * 车云协议消息信封(协议 §3.1)。
 *
 * <p>所有 MQTT 报文统一使用本 record 承载。Payload 类型 {@code T} 随 {@link DiagAction} 变化,
 * 序列化时通过 Jackson 的 Generic 类型推断;反序列化需要通过 {@link EnvelopeReader} 两阶段解析
 * (先取 act,再按 act 映射 payload 类型)。
 *
 * <p>字段序列约定(为 {@code payloadHash} 规范化的一部分):
 * <ol>
 *   <li>JSON 字段按 UTF-16 字典序,由 JCS(RFC 8785)处理</li>
 *   <li>{@code operator} 为 null 时不输出字段(Jackson NON_NULL)</li>
 *   <li>{@code traceparent} 为 W3C Trace Context,可空</li>
 * </ol>
 *
 * @param msgId       全局唯一消息 ID;使用 {@link #newMsgId()} 生成 UUID v4
 * @param timestamp   报文生成时的 Unix 毫秒时间戳(发送侧本地时钟)
 * @param vin         目标车架号,17 位
 * @param act         业务动作类型
 * @param operator    操作者上下文,C2V 必填,V2C 主动事件可空
 * @param traceparent W3C Trace Context;车端/云端若支持 OTEL 则携带,用于跨进程 trace 串联
 * @param payload     业务数据对象
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagMessage<T>(
        String msgId,
        long timestamp,
        String vin,
        DiagAction act,
        Operator operator,
        String traceparent,
        T payload) {

    public DiagMessage {
        Objects.requireNonNull(msgId, "msgId 必填");
        Objects.requireNonNull(vin, "vin 必填");
        Objects.requireNonNull(act, "act 必填");
        if (vin.length() != 17) {
            throw new IllegalArgumentException("vin 必须为 17 位: " + vin);
        }
    }

    /**
     * 构建 C2V 报文:必带 operator。
     */
    public static <P> DiagMessage<P> c2v(String vin, DiagAction act, Operator operator, P payload) {
        Objects.requireNonNull(operator, "C2V 报文 operator 必填(协议 §3.3)");
        return new DiagMessage<>(newMsgId(), System.currentTimeMillis(), vin, act, operator, null, payload);
    }

    /**
     * 构建 V2C 车端主动事件报文:operator 可空(由云端标注 system)。
     */
    public static <P> DiagMessage<P> v2cSystemEvent(String vin, DiagAction act, P payload) {
        return new DiagMessage<>(newMsgId(), System.currentTimeMillis(), vin, act, null, null, payload);
    }

    /**
     * 构建 V2C 响应报文:operator 原样回填(车端从 operator_cache 取);若丢失由云端 msgId 反查补全。
     */
    public static <P> DiagMessage<P> v2cResponse(String vin, DiagAction act, Operator operator, P payload) {
        return new DiagMessage<>(newMsgId(), System.currentTimeMillis(), vin, act, operator, null, payload);
    }

    public static String newMsgId() {
        return UUID.randomUUID().toString();
    }
}
