package com.opendota.simulator;

import com.opendota.common.util.HexUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * CAN / DoIP 共用的 UDS 请求→响应映射表(架构 §8.3)。
 *
 * <p>覆盖常见 UDS 服务的 minimal 响应,便于 Phase 0/Phase 1 自测。真实车端行为由
 * Rust Agent 提供。本映射表**只处理 raw UDS PDU**,宏指令(macro_security/routine_wait/
 * data_transfer)在 {@code MqttVehicleSimulator} 层各自模拟。
 *
 * <p>支持的请求前缀(按 UDS 服务 ID 匹配):
 * <ul>
 *   <li>{@code 22 F1 90} → {@code 62 F1 90 4C 53 56 57 41 32 33 34 35 36 37 38 39 30 31 32 33}(读 VIN)</li>
 *   <li>{@code 22 F1 91} → {@code 62 F1 91 48 57 5F 56 31 2E 30}(读硬件版本)</li>
 *   <li>{@code 22 F1 12} → {@code 62 F1 12 3B}(读电池温度 59°C,raw=0x3B)</li>
 *   <li>{@code 10 03} → {@code 50 03 00 C8 00 14}(切换扩展会话)</li>
 *   <li>{@code 10 01} → {@code 50 01 00 C8 00 14}(切换默认会话)</li>
 *   <li>{@code 14 FF FF FF} → {@code 54}(清除所有 DTC)</li>
 *   <li>{@code 19 02 09} → {@code 59 02 09 01 00 01 8F}(读 1 个故障码 P0100)</li>
 *   <li>{@code 3E 80} → 不响应(抑制正响应的 TesterPresent)</li>
 *   <li>其它 → {@code 7F <SID> 31}(NRC_31 RequestOutOfRange)</li>
 * </ul>
 */
public class CanMockResponder {

    private final Map<String, String> responseMap = new HashMap<>();

    public CanMockResponder() {
        // 读数据类(0x22)
        responseMap.put("22f190", "62f1904c53565741323334353637383930313233");
        responseMap.put("22f191", "62f19148575f56312e30");
        responseMap.put("22f193", "62f19342415454");
        responseMap.put("22f195", "62f19553575f56322e33");
        responseMap.put("22f112", "62f1123b");

        // 会话切换(0x10)
        responseMap.put("1001", "500100c80014");
        responseMap.put("1003", "500300c80014");
        responseMap.put("1002", "500200c80014");

        // DTC 操作
        responseMap.put("14ffffff", "54");
        responseMap.put("190209", "5902090100018f");
        responseMap.put("190204", "590204"); // 无 DTC

        // ECU 复位(0x11)
        responseMap.put("1101", "5101");
        responseMap.put("1103", "5103");
    }

    /**
     * 对给定请求 PDU 查表返回响应 hex(小写)。未命中返回 NRC_31。
     *
     * @param requestHex 请求 UDS PDU 的小写 hex 字符串,如 {@code "22f190"}
     * @return 响应 UDS PDU 的小写 hex 字符串
     */
    public String respond(String requestHex) {
        if (requestHex == null || requestHex.isEmpty()) {
            return "7f0031";
        }
        String normalized = requestHex.toLowerCase();

        // 3E 80 抑制正响应,返回 null 表示不回
        if (normalized.startsWith("3e80")) {
            return null;
        }

        // 精确匹配
        if (responseMap.containsKey(normalized)) {
            return responseMap.get(normalized);
        }

        // 前缀匹配(如 2EF190[DATA] 写数据,不关心 data 尾巴)
        for (Map.Entry<String, String> e : responseMap.entrySet()) {
            if (normalized.startsWith(e.getKey())) {
                return e.getValue();
            }
        }

        // 默认:NRC_31 RequestOutOfRange
        String sid = normalized.length() >= 2 ? normalized.substring(0, 2) : "00";
        return "7f" + sid + "31";
    }

    /**
     * 供运行时动态注册新的响应。便于测试注入特定场景(如模拟 NRC_78 response pending)。
     */
    public void register(String requestHex, String responseHex) {
        responseMap.put(requestHex.toLowerCase(), responseHex.toLowerCase());
    }

    /**
     * 判定给定请求是否为 "不应响应"(如 3E 80 抑制正响应)。
     */
    public boolean shouldSuppressResponse(String requestHex) {
        if (requestHex == null) return true;
        String normalized = requestHex.toLowerCase();
        return normalized.startsWith("3e80");
    }

    /**
     * 验证 hex 字符串合法,失败返回 null。
     */
    public static byte[] parseHexSafe(String hex) {
        try {
            return HexUtils.fromHex(hex);
        } catch (Exception e) {
            return null;
        }
    }
}
