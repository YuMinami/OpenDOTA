package com.opendota.task.lifecycle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * EMQX Rule Engine 推送到 Kafka {@code vehicle-lifecycle} topic 的事件。
 *
 * <p>对应架构 §4.6.2 中 EMQX SQL 规则提取的字段:
 * <pre>
 * SELECT clientid, username AS vin, event, peername, cert_subject, now_timestamp() AS at
 * FROM '$events/client_connected', '$events/client_disconnected'
 * </pre>
 *
 * @param vin      MQTT username,即车辆 VIN
 * @param event    事件类型: {@code connected} 或 {@code disconnected}
 * @param clientId MQTT Client ID
 * @param peername 客户端 IP:端口
 * @param certSubject mTLS 证书 Subject(CN=VIN)
 * @param at       事件发生时间戳(毫秒)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VehicleLifecycleEvent(
        @JsonProperty("vin") String vin,
        @JsonProperty("event") String event,
        @JsonProperty("clientid") String clientId,
        @JsonProperty("peername") String peername,
        @JsonProperty("cert_subject") String certSubject,
        @JsonProperty("at") long at
) {

    public boolean isConnected() {
        return "connected".equals(event);
    }

    public boolean isDisconnected() {
        return "disconnected".equals(event);
    }
}
