package com.opendota.diag.cmd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.envelope.Operator;
import com.opendota.common.envelope.OperatorRole;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.web.GlobalExceptionHandler;
import com.opendota.diag.web.ResponseWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SingleCmdControllerTest {

    private MockMvc mockMvc;
    private SingleCmdService singleCmdService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        singleCmdService = mock(SingleCmdService.class);
        when(singleCmdService.dispatch(any(), any())).thenReturn("550e8400-e29b-41d4-a716-446655440000");

        SingleCmdController controller = new SingleCmdController(singleCmdService, new OperatorContextResolver());
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
    void singleShouldReturnMsgIdAndResolveOperatorHeaders() throws Exception {
        String body = """
                {
                  "channelId": "ch-123",
                  "type": "raw_uds",
                  "reqData": "22F190",
                  "timeoutMs": 5000
                }
                """;

        mockMvc.perform(post("/api/cmd/single")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, "eng-12345")
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, "chery-hq")
                        .header(OperatorContextResolver.HDR_TICKET_ID, "DIAG-2026-0417-001")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.msgId").value("550e8400-e29b-41d4-a716-446655440000"));

        verify(singleCmdService).dispatch(
                eq(new SingleCmdController.SingleCmdRequest("ch-123", "raw_uds", "22F190", 5000)),
                eq(new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-2026-0417-001")));
    }
}
