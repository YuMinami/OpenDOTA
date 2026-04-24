package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DoIP 连接配置。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DoipConfig(
        String ecuIp,
        Integer ecuPort,
        String activationType,
        String workstationId) {

    public DoipConfig {
        ecuPort = ecuPort == null ? 13400 : ecuPort;
        activationType = activationType == null ? "0x00" : activationType;
    }
}
