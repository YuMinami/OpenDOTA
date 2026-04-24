package com.opendota.common.payload.common;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * UDS 传输层类型。
 *
 * <p>协议 / schema 当前统一使用大写 wire 值 {@code UDS_ON_CAN}/{@code UDS_ON_DOIP},
 * 反序列化额外兼容大小写差异,避免客户端因大小写问题直接失败。
 */
public enum Transport {

    UDS_ON_CAN("UDS_ON_CAN"),
    UDS_ON_DOIP("UDS_ON_DOIP");

    private final String wireName;

    Transport(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static Transport of(String wireName) {
        if (wireName == null) {
            return null;
        }
        for (Transport transport : values()) {
            if (transport.wireName.equalsIgnoreCase(wireName)) {
                return transport;
            }
        }
        throw new IllegalArgumentException("未知 Transport: " + wireName);
    }
}
