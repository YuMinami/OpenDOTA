package com.opendota.diag.arbitration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LockOrderPolicy — 字典序锁排序策略")
class LockOrderPolicyTest {

    private static final String VIN = "LSVWA234567890123";

    @Nested
    @DisplayName("orderedLockSpecs")
    class OrderedLockSpecs {

        @Test
        @DisplayName("单 ECU:返回单个 spec")
        void singleEcu() {
            List<LockOrderPolicy.LockSpec> specs = LockOrderPolicy.orderedLockSpecs(VIN, List.of("VCU"));
            assertThat(specs).hasSize(1);
            assertThat(specs.getFirst().ecuName()).isEqualTo("VCU");
            assertThat(specs.getFirst().lockKey()).isEqualTo("ecu:lock:" + VIN + ":VCU");
        }

        @Test
        @DisplayName("多 ECU:按字典序排列")
        void multiEcuSorted() {
            List<LockOrderPolicy.LockSpec> specs =
                    LockOrderPolicy.orderedLockSpecs(VIN, List.of("VCU", "BMS", "EPS"));
            assertThat(specs).extracting(LockOrderPolicy.LockSpec::ecuName)
                    .containsExactly("BMS", "EPS", "VCU");
        }

        @Test
        @DisplayName("输入乱序:输出仍为字典序")
        void inputUnordered() {
            List<LockOrderPolicy.LockSpec> specs =
                    LockOrderPolicy.orderedLockSpecs(VIN, List.of("GW", "VCU", "BMS"));
            assertThat(specs).extracting(LockOrderPolicy.LockSpec::ecuName)
                    .containsExactly("BMS", "GW", "VCU");
        }

        @Test
        @DisplayName("输入已排序:输出顺序不变")
        void inputAlreadySorted() {
            List<LockOrderPolicy.LockSpec> specs =
                    LockOrderPolicy.orderedLockSpecs(VIN, List.of("BMS", "GW", "VCU"));
            assertThat(specs).extracting(LockOrderPolicy.LockSpec::ecuName)
                    .containsExactly("BMS", "GW", "VCU");
        }

        @Test
        @DisplayName("ecuScope 为空:抛 IllegalArgumentException")
        void emptyScope() {
            assertThatThrownBy(() -> LockOrderPolicy.orderedLockSpecs(VIN, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ecuScope 必填");
        }

        @Test
        @DisplayName("vin 为空白:抛 IllegalArgumentException")
        void blankVin() {
            assertThatThrownBy(() -> LockOrderPolicy.orderedLockSpecs("   ", List.of("VCU")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("vin");
        }

        @Test
        @DisplayName("vin 为 null:抛 NullPointerException")
        void nullVin() {
            assertThatThrownBy(() -> LockOrderPolicy.orderedLockSpecs(null, List.of("VCU")))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("buildLockKey")
    class BuildLockKey {

        @Test
        @DisplayName("标准格式:ecu:lock:{vin}:{ecuName}")
        void standardFormat() {
            String key = LockOrderPolicy.buildLockKey("LSVWA234567890123", "BMS");
            assertThat(key).isEqualTo("ecu:lock:LSVWA234567890123:BMS");
        }

        @Test
        @DisplayName("不同 VIN 产生不同 key")
        void differentVinDifferentKey() {
            String key1 = LockOrderPolicy.buildLockKey("LSVWA234567890123", "VCU");
            String key2 = LockOrderPolicy.buildLockKey("LSVWB234567890456", "VCU");
            assertThat(key1).isNotEqualTo(key2);
        }

        @Test
        @DisplayName("不同 ECU 产生不同 key")
        void differentEcuDifferentKey() {
            String key1 = LockOrderPolicy.buildLockKey(VIN, "VCU");
            String key2 = LockOrderPolicy.buildLockKey(VIN, "BMS");
            assertThat(key1).isNotEqualTo(key2);
        }
    }
}
