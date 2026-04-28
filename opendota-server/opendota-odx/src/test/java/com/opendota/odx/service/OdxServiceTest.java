package com.opendota.odx.service;

import com.opendota.odx.entity.OdxDiagService;
import com.opendota.odx.entity.OdxEcu;
import com.opendota.odx.entity.OdxParamCodec;
import com.opendota.odx.entity.OdxVehicleModel;
import com.opendota.odx.repository.OdxEcuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2 Step 2.2 OdxService 单元测试。
 *
 * <p>覆盖:
 * <ul>
 *   <li>服务目录树按 category 分组、组内顺序保留 Repository 排序</li>
 *   <li>ecu-scope 推导:gateway_chain 非空 → ["BMS","GW"] 字典序;空 → [ecuName]</li>
 *   <li>VIN/ECU 不存在抛 {@link OdxResourceNotFoundException}</li>
 * </ul>
 */
class OdxServiceTest {

    private OdxEcuRepository repository;
    private OdxService service;

    @BeforeEach
    void setUp() {
        repository = mock(OdxEcuRepository.class);
        service = new OdxService(repository);
    }

    // ============== getServiceCatalog ==============

    @Test
    void getServiceCatalog_buildsCategoryTree() {
        OdxEcu vcu = ecu(1L, "VCU 整车控制器", "0x7E0", "0x7E8", "UDS_ON_CAN", List.of());
        when(repository.findEcuById(1L)).thenReturn(Optional.of(vcu));
        when(repository.findServicesByEcuId(1L)).thenReturn(List.of(
                diagService(101L, "识别信息", "读取车辆识别码(VIN)", "0x22", "F190", "22F190"),
                diagService(102L, "运行数据", "读取电池温度", "0x22", "F112", "22F112"),
                diagService(103L, "会话控制", "进入扩展会话", "0x10", "03", "1003"),
                diagService(104L, "安全访问", "请求安全解锁(Lv1)", "0x27", "01", "2701")));

        EcuServiceCatalog catalog = service.getServiceCatalog(1L);

        assertThat(catalog.ecuId()).isEqualTo(1L);
        assertThat(catalog.ecuName()).isEqualTo("VCU 整车控制器");
        assertThat(catalog.txId()).isEqualTo("0x7E0");
        assertThat(catalog.categories()).hasSize(4);
        assertThat(catalog.categories())
                .extracting(EcuServiceCatalog.Category::category)
                .containsExactly("识别信息", "运行数据", "会话控制", "安全访问");

        EcuServiceCatalog.Category identity = catalog.categories().get(0);
        assertThat(identity.services()).hasSize(1);
        EcuServiceCatalog.ServiceItem readVin = identity.services().get(0);
        assertThat(readVin.id()).isEqualTo(101L);
        assertThat(readVin.requestRawHex()).isEqualTo("22F190");
        assertThat(readVin.subFunction()).isEqualTo("F190");
    }

    @Test
    void getServiceCatalog_groupsMultipleServicesPerCategory() {
        OdxEcu gw = ecu(2L, "GW 中央网关(以太网)", "0x0E80", "0x1010", "UDS_ON_DOIP", List.of());
        when(repository.findEcuById(2L)).thenReturn(Optional.of(gw));
        when(repository.findServicesByEcuId(2L)).thenReturn(List.of(
                diagService(201L, "识别信息", "读取网关软件版本号", "0x22", "F195", "22F195"),
                diagService(202L, "识别信息", "读取网关硬件版本号", "0x22", "F191", "22F191")));

        EcuServiceCatalog catalog = service.getServiceCatalog(2L);

        assertThat(catalog.categories()).hasSize(1);
        assertThat(catalog.categories().get(0).services())
                .extracting(EcuServiceCatalog.ServiceItem::id)
                .containsExactly(201L, 202L);
    }

    @Test
    void getServiceCatalog_missingEcuThrows() {
        when(repository.findEcuById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getServiceCatalog(999L))
                .isInstanceOf(OdxResourceNotFoundException.class)
                .hasMessageContaining("ecu 不存在")
                .hasMessageContaining("999");
    }

    // ============== resolveEcuScope ==============

    @Test
    void resolveEcuScope_withGatewayChainSortedDictionary() {
        OdxVehicleModel model = new OdxVehicleModel(
                1L, "chery-hq", "CHERY_EXEED", "奇瑞 星途瑶光", "v1.0_mock");
        when(repository.findVehicleModelByVin("LSVWA234567890123")).thenReturn(Optional.of(model));
        OdxEcu bms = ecu(3L, "BMS 电池管理系统", "0x7E3", "0x7EB", "UDS_ON_CAN", List.of("GW"));
        when(repository.findEcuByName(1L, "BMS")).thenReturn(Optional.of(bms));

        EcuScopeResolution res = service.resolveEcuScope("LSVWA234567890123", "BMS");

        // 协议 §10.2.3 字典序:["BMS","GW"](B < G)
        assertThat(res.ecuScope()).containsExactly("BMS", "GW");
        assertThat(res.vin()).isEqualTo("LSVWA234567890123");
        assertThat(res.modelCode()).isEqualTo("CHERY_EXEED");
        assertThat(res.requested()).isEqualTo("BMS");
        assertThat(res.reason()).contains("GW", "网关");
    }

    @Test
    void resolveEcuScope_emptyChainReturnsSelfOnly() {
        OdxVehicleModel model = new OdxVehicleModel(
                1L, "chery-hq", "CHERY_EXEED", "奇瑞 星途瑶光", "v1.0_mock");
        when(repository.findVehicleModelByVin("LSVWA234567890123")).thenReturn(Optional.of(model));
        OdxEcu vcu = ecu(1L, "VCU 整车控制器", "0x7E0", "0x7E8", "UDS_ON_CAN", List.of());
        when(repository.findEcuByName(1L, "VCU")).thenReturn(Optional.of(vcu));

        EcuScopeResolution res = service.resolveEcuScope("LSVWA234567890123", "VCU");

        assertThat(res.ecuScope()).containsExactly("VCU");
        assertThat(res.reason()).contains("直连 OBD");
    }

    @Test
    void resolveEcuScope_unknownVinThrows() {
        when(repository.findVehicleModelByVin("UNKNOWN_VIN_00000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveEcuScope("UNKNOWN_VIN_00000", "BMS"))
                .isInstanceOf(OdxResourceNotFoundException.class)
                .hasMessageContaining("vin")
                .hasMessageContaining("UNKNOWN_VIN_00000");
    }

    @Test
    void resolveEcuScope_unknownEcuInModelThrows() {
        OdxVehicleModel model = new OdxVehicleModel(
                1L, "chery-hq", "CHERY_EXEED", "奇瑞 星途瑶光", "v1.0_mock");
        when(repository.findVehicleModelByVin("LSVWA234567890123")).thenReturn(Optional.of(model));
        when(repository.findEcuByName(1L, "ABS")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveEcuScope("LSVWA234567890123", "ABS"))
                .isInstanceOf(OdxResourceNotFoundException.class)
                .hasMessageContaining("ABS")
                .hasMessageContaining("CHERY_EXEED");
    }

    // ============== translateSingleResponse ==============

    @Test
    void translateSingleResponse_positiveShouldDecodeParameters() {
        OdxVehicleModel model = new OdxVehicleModel(
                1L, "chery-hq", "CHERY_EXEED", "奇瑞 星途瑶光", "v1.0_mock");
        OdxEcu vcu = ecu(1L, "VCU 整车控制器", "0x7E0", "0x7E8", "UDS_ON_CAN", List.of());
        OdxDiagService readTemp = diagService(102L, "运行数据", "读取电池温度", "0x22", "F112", "22F112");

        when(repository.findVehicleModelByVin("LSVWA234567890123")).thenReturn(Optional.of(model));
        when(repository.findEcuByName(1L, "VCU")).thenReturn(Optional.of(vcu));
        when(repository.findServicesByEcuId(1L)).thenReturn(List.of(readTemp));
        when(repository.findParamCodecsByServiceId(102L)).thenReturn(List.of(
                new OdxParamCodec(
                        1L, 102L, "BattTemp", "电池温度探针 A",
                        3, 0, 8, "unsigned", "raw * 1 - 40", "°C",
                        null, BigDecimal.valueOf(-40), BigDecimal.valueOf(120))));

        Map<String, Object> translated = service.translateSingleResponse(
                "LSVWA234567890123", "VCU", "22F112", "62F1123B");

        assertThat(translated.get("translationType")).isEqualTo("positive");
        assertThat(translated.get("serviceDisplayName")).isEqualTo("读取电池温度");
        assertThat(translated.get("summaryText")).isEqualTo("读取电池温度: 电池温度探针 A=19°C");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) translated.get("parameters");
        assertThat(parameters).hasSize(1);
        assertThat(parameters.get(0).get("rawDecimal")).isEqualTo(59L);
        assertThat(parameters.get(0).get("physicalValue")).isEqualTo(new BigDecimal("19"));
    }

    @Test
    void translateSingleResponse_negativeShouldReturnNrcInfo() {
        OdxVehicleModel model = new OdxVehicleModel(
                1L, "chery-hq", "CHERY_EXEED", "奇瑞 星途瑶光", "v1.0_mock");
        OdxEcu vcu = ecu(1L, "VCU 整车控制器", "0x7E0", "0x7E8", "UDS_ON_CAN", List.of());
        OdxDiagService readVin = diagService(101L, "识别信息", "读取车辆识别码(VIN)", "0x22", "F190", "22F190");

        when(repository.findVehicleModelByVin("LSVWA234567890123")).thenReturn(Optional.of(model));
        when(repository.findEcuByName(1L, "VCU")).thenReturn(Optional.of(vcu));
        when(repository.findServicesByEcuId(1L)).thenReturn(List.of(readVin));

        Map<String, Object> translated = service.translateSingleResponse(
                "LSVWA234567890123", "VCU", "22F190", "7F2222");

        assertThat(translated.get("translationType")).isEqualTo("negative");
        assertThat(translated.get("nrcCode")).isEqualTo("0x22");
        assertThat(translated.get("nrcDisplayName")).isEqualTo("当前条件不满足");
        assertThat(translated.get("summaryText")).isEqualTo("读取车辆识别码(VIN) 失败: 当前条件不满足");
    }

    // ============== helpers ==============

    private static OdxEcu ecu(long id, String name, String txId, String rxId,
                              String protocol, List<String> chain) {
        return new OdxEcu(id, 1L, name.split(" ", 2)[0] + "_V1", name,
                txId, rxId, protocol, null, chain);
    }

    private static OdxDiagService diagService(long id, String category, String displayName,
                                              String serviceCode, String subFunction,
                                              String requestRawHex) {
        return new OdxDiagService(
                id, 1L, serviceCode, subFunction, "Svc_" + id, displayName,
                null, category, requestRawHex, "—", false, null, "01", "raw_uds", true, false);
    }
}
