package com.opendota.diag.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.payload.channel.ChannelClosePayload;
import com.opendota.common.payload.channel.ChannelOpenPayload;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.web.GlobalExceptionHandler;
import com.opendota.diag.web.ResponseWrapper;
import com.opendota.mqtt.publisher.MqttPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 Step 2.1 通道生命周期端点测试。
 *
 * <p>覆盖符合性用例:
 * <ul>
 *   <li>B-1 ecuScope 缺失 → {@code code=41002}(协议附录 D §3.1)</li>
 *   <li>B-2 单 ECU 开通道成功 → 返回 {@code channelId / msgId},MQTT 投递 {@code channel_open}
 *       envelope,{@link ChannelManager} 已登记</li>
 * </ul>
 *
 * <p>另外覆盖关通道路径:成功开通后再调 close 必须发 {@code channel_close} 报文并清理本地登记。
 */
class ChannelControllerTest {

    private static final String VIN = "LSVWA234567890123";

    private MockMvc mockMvc;
    private MqttPublisher publisher;
    private ChannelManager channelManager;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        publisher = mock(MqttPublisher.class);
        doNothing().when(publisher).publish(any(DiagMessage.class));

        channelManager = new ChannelManager();
        ChannelOpenService openService = new ChannelOpenService(publisher, channelManager);
        ChannelCloseService closeService = new ChannelCloseService(publisher, channelManager);
        ChannelController controller = new ChannelController(
                openService, closeService, new OperatorContextResolver());

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
    void b1_missingEcuScopeShouldBeRejected() throws Exception {
        String body = """
                {
                  "vin": "%s",
                  "ecuName": "VCU",
                  "transport": "UDS_ON_CAN",
                  "txId": "0x7E0",
                  "rxId": "0x7E8",
                  "globalTimeoutMs": 300000
                }
                """.formatted(VIN);

        mockMvc.perform(post("/api/channel/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, "engineer-001")
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, "default-tenant")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(41002))
                .andExpect(jsonPath("$.msg", startsWith("ecuScope 必填")));

        assertEquals(0, channelManager.size(), "拒绝场景下 ChannelManager 不应登记任何通道");
    }

    @Test
    void b2_singleEcuOpenShouldReturnChannelIdAndPublishEnvelope() throws Exception {
        String body = """
                {
                  "vin": "%s",
                  "ecuName": "VCU",
                  "ecuScope": ["VCU"],
                  "transport": "UDS_ON_CAN",
                  "txId": "0x7E0",
                  "rxId": "0x7E8",
                  "globalTimeoutMs": 300000
                }
                """.formatted(VIN);

        MvcResult result = mockMvc.perform(post("/api/channel/open")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, "engineer-001")
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, "default-tenant")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.channelId", startsWith("ch-")))
                .andExpect(jsonPath("$.data.msgId",
                        matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
                .andReturn();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DiagMessage<?>> captor = ArgumentCaptor.forClass(DiagMessage.class);
        verify(publisher).publish(captor.capture());

        DiagMessage<?> envelope = captor.getValue();
        assertEquals(DiagAction.CHANNEL_OPEN, envelope.act());
        assertEquals(VIN, envelope.vin());

        assertTrue(envelope.payload() instanceof ChannelOpenPayload, "envelope 必须使用类型化 payload");
        ChannelOpenPayload payload = (ChannelOpenPayload) envelope.payload();
        assertTrue(payload.channelId().startsWith("ch-"));
        assertEquals("VCU", payload.ecuName());
        assertEquals(java.util.List.of("VCU"), payload.ecuScope());
        assertEquals("UDS_ON_CAN", payload.transport().wireName());

        assertEquals(1, channelManager.size(), "成功开通后 ChannelManager 必须登记一条通道");
        String channelId = payload.channelId();
        assertTrue(channelManager.get(channelId).isPresent(), "channelId 应登记");
        assertEquals("VCU", channelManager.get(channelId).orElseThrow().getEcuName());

        // 顺手覆盖 close 路径,确认 channel_close envelope 真的发出去了
        mockMvc.perform(post("/api/channel/{channelId}/close", channelId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, "engineer-001")
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, "default-tenant")
                        .content("{\"resetSession\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.channelId").value(channelId))
                .andExpect(jsonPath("$.data.vin").value(VIN));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DiagMessage<?>> closeCaptor = ArgumentCaptor.forClass(DiagMessage.class);
        verify(publisher, org.mockito.Mockito.times(2)).publish(closeCaptor.capture());
        DiagMessage<?> closeEnv = closeCaptor.getAllValues().get(1);
        assertEquals(DiagAction.CHANNEL_CLOSE, closeEnv.act());
        assertTrue(closeEnv.payload() instanceof ChannelClosePayload);
        ChannelClosePayload closePayload = (ChannelClosePayload) closeEnv.payload();
        assertEquals(channelId, closePayload.channelId());
        assertEquals(Boolean.TRUE, closePayload.resetSession());
        assertEquals(0, channelManager.size(), "关通道后本地登记必须清理");

        // 防止 IDE 误报 result 未被使用
        assertTrue(result.getResponse().getStatus() == 200);
    }

    @Test
    void closeUnknownChannelShouldReturn40401() throws Exception {
        mockMvc.perform(post("/api/channel/{channelId}/close", "ch-does-not-exist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, "engineer-001")
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, "default-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401));

        org.mockito.Mockito.verifyNoInteractions(publisher);
    }
}
