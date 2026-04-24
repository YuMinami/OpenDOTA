package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 多 ECU 脚本中的单个 ECU 执行块。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EcuBlock(
        String ecuName,
        List<String> ecuScope,
        Transport transport,
        String txId,
        String rxId,
        DoipConfig doipConfig,
        Integer strategy,
        List<Step> steps) {

    public EcuBlock {
        ecuScope = ecuScope == null ? null : EcuScope.normalize(ecuScope);
        transport = transport == null ? Transport.UDS_ON_CAN : transport;
        strategy = strategy == null ? 1 : strategy;
        steps = steps == null ? null : List.copyOf(steps);
    }
}
