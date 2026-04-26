package com.opendota.diag.odx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.diag.web.GlobalExceptionHandler;
import com.opendota.diag.web.ResponseWrapper;
import com.opendota.odx.entity.OdxDiagService;
import com.opendota.odx.entity.OdxEcu;
import com.opendota.odx.entity.OdxVehicleModel;
import com.opendota.odx.repository.OdxEcuRepository;
import com.opendota.odx.service.OdxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Step 2.2 OdxController 端到端测试。
 *
 * <p>覆盖验收项:
 * <ul>
 *   <li>{@code GET /api/odx/services?ecuId=}:mock 数据下 3 个 ECU 共 7 个服务</li>
 *   <li>{@code GET /api/vehicle/{vin}/ecu-scope?ecuName=BMS}:返回 ["BMS","GW"]</li>
 *   <li>不存在的 ecuId / VIN / ecuName → {@code code=40401}(GlobalExceptionHandler 映射)</li>
 * </ul>
 *
 * <p>测试通过 stub {@link OdxEcuRepository} 模拟 mock 数据,Service + Controller +
 * GlobalExceptionHandler + ResponseWrapper 都走真实实现,等价于针对 mock seed 的离线 E2E。
 */
class OdxControllerTest {

    private static final String VIN = "LSVWA234567890123";

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        OdxEcuRepository repository = mock(OdxEcuRepository.class);
        seedRepository(repository);
        OdxService odxService = new OdxService(repository);
        OdxController controller = new OdxController(odxService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(
                        new GlobalExceptionHandler(),
                        new ResponseWrapper(objectMapper))
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(objectMapper),
                        new StringHttpMessageConverter(StandardCharsets.UTF_8))
                .build();
    }

    @Test
    void mockSeedHas3EcusAnd7Services() throws Exception {
        int totalServices = 0;
        for (long ecuId : new long[]{1L, 2L, 3L}) {
            MvcResult result = mockMvc.perform(get("/api/odx/services").param("ecuId", String.valueOf(ecuId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.ecuId").value((int) ecuId))
                    .andReturn();

            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
            for (JsonNode category : data.path("categories")) {
                totalServices += category.path("services").size();
            }
        }
        assertThat(totalServices)
                .as("mock 数据下三个 ECU(VCU/GW/BMS)共应包含 7 条诊断服务")
                .isEqualTo(7);
    }

    @Test
    void servicesEndpointReturnsCategorizedTreeForVcu() throws Exception {
        mockMvc.perform(get("/api/odx/services").param("ecuId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ecuName").value("VCU 整车控制器"))
                .andExpect(jsonPath("$.data.txId").value("0x7E0"))
                .andExpect(jsonPath("$.data.categories[0].category").value("会话控制"))
                .andExpect(jsonPath("$.data.categories[0].services[0].requestRawHex").value("1003"));
    }

    @Test
    void servicesEndpointMissingEcuReturns40401() throws Exception {
        mockMvc.perform(get("/api/odx/services").param("ecuId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("ecu 不存在")));
    }

    @Test
    void ecuScopeReturnsBmsGwChain() throws Exception {
        mockMvc.perform(get("/api/vehicle/{vin}/ecu-scope", VIN).param("ecuName", "BMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.vin").value(VIN))
                .andExpect(jsonPath("$.data.modelCode").value("CHERY_EXEED"))
                .andExpect(jsonPath("$.data.requested").value("BMS"))
                .andExpect(jsonPath("$.data.ecuScope[0]").value("BMS"))
                .andExpect(jsonPath("$.data.ecuScope[1]").value("GW"))
                .andExpect(jsonPath("$.data.reason", org.hamcrest.Matchers.containsString("GW")));
    }

    @Test
    void ecuScopeForVcuReturnsSelfOnly() throws Exception {
        mockMvc.perform(get("/api/vehicle/{vin}/ecu-scope", VIN).param("ecuName", "VCU"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.ecuScope[0]").value("VCU"))
                .andExpect(jsonPath("$.data.reason", org.hamcrest.Matchers.containsString("直连 OBD")));
    }

    @Test
    void ecuScopeUnknownVinReturns40401() throws Exception {
        mockMvc.perform(get("/api/vehicle/{vin}/ecu-scope", "UNKNOWN0000000000")
                        .param("ecuName", "BMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("vin")));
    }

    @Test
    void ecuScopeUnknownEcuReturns40401() throws Exception {
        mockMvc.perform(get("/api/vehicle/{vin}/ecu-scope", VIN).param("ecuName", "ABS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("ABS")));
    }

    /** 镜像 R__mock_seed.sql 的 ODX 数据,供 Service+Controller 端到端断言。 */
    private static void seedRepository(OdxEcuRepository repository) {
        OdxVehicleModel model = new OdxVehicleModel(
                1L, "chery-hq", "CHERY_EXEED", "奇瑞 星途瑶光", "v1.0_mock");
        when(repository.findVehicleModelByVin(VIN)).thenReturn(Optional.of(model));
        when(repository.findVehicleModelByVin("UNKNOWN0000000000")).thenReturn(Optional.empty());

        OdxEcu vcu = new OdxEcu(1L, 1L, "VCU_BAS_V1", "VCU 整车控制器",
                "0x7E0", "0x7E8", "UDS_ON_CAN", "Algo_Chery_VCU", List.of());
        OdxEcu gw = new OdxEcu(2L, 1L, "GW_ETH_V1", "GW 中央网关(以太网)",
                "0x0E80", "0x1010", "UDS_ON_DOIP", null, List.of());
        OdxEcu bms = new OdxEcu(3L, 1L, "BMS_V1", "BMS 电池管理系统",
                "0x7E3", "0x7EB", "UDS_ON_CAN", "Algo_Chery_BMS", List.of("GW"));

        when(repository.findEcuById(1L)).thenReturn(Optional.of(vcu));
        when(repository.findEcuById(2L)).thenReturn(Optional.of(gw));
        when(repository.findEcuById(3L)).thenReturn(Optional.of(bms));
        when(repository.findEcuById(999L)).thenReturn(Optional.empty());

        when(repository.findEcuByName(1L, "VCU")).thenReturn(Optional.of(vcu));
        when(repository.findEcuByName(1L, "GW")).thenReturn(Optional.of(gw));
        when(repository.findEcuByName(1L, "BMS")).thenReturn(Optional.of(bms));
        when(repository.findEcuByName(1L, "ABS")).thenReturn(Optional.empty());

        // 服务列表已按 (category ASC, id ASC) 排序,与 Repository SQL 一致
        when(repository.findServicesByEcuId(1L)).thenReturn(List.of(
                svc(103L, 1L, "0x10", "03", "DiagSession_Extended", "进入扩展会话",
                        "会话控制", "1003", "5003", "raw_uds", null),
                svc(101L, 1L, "0x22", "F190", "Read_VIN", "读取车辆识别码(VIN)",
                        "识别信息", "22F190", "62F190", "raw_uds", "01"),
                svc(102L, 1L, "0x22", "F112", "Read_BattTemp", "读取电池温度",
                        "运行数据", "22F112", "62F112", "raw_uds", "01"),
                svc(104L, 1L, "0x27", "01", "SecAccess_Lv1", "请求安全解锁(Lv1)",
                        "安全访问", "2701", "6701", "macro_security", "03")));
        when(repository.findServicesByEcuId(2L)).thenReturn(List.of(
                svc(201L, 2L, "0x22", "F195", "Read_GW_SwVer", "读取网关软件版本号",
                        "识别信息", "22F195", "62F195", "raw_uds", "01"),
                svc(202L, 2L, "0x22", "F191", "Read_GW_HwVer", "读取网关硬件版本号",
                        "识别信息", "22F191", "62F191", "raw_uds", "01")));
        when(repository.findServicesByEcuId(3L)).thenReturn(List.of(
                svc(301L, 3L, "0x19", "0209", "Read_BMS_DTC", "读取 BMS 故障码",
                        "故障诊断", "190209", "590209", "raw_uds", "01")));
    }

    private static OdxDiagService svc(long id, long ecuId, String code, String sub,
                                      String name, String displayName, String category,
                                      String reqHex, String respHex, String macro, String session) {
        return new OdxDiagService(id, ecuId, code, sub, name, displayName, null, category,
                reqHex, respHex, false, null, session, macro, true, false);
    }
}
