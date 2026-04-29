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

class ScriptCmdControllerTest {

    private MockMvc mockMvc;
    private ScriptCmdService scriptCmdService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        scriptCmdService = mock(ScriptCmdService.class);
        when(scriptCmdService.dispatch(any(), any())).thenReturn("550e8400-e29b-41d4-a716-446655440099");

        ScriptCmdController controller = new ScriptCmdController(scriptCmdService, new OperatorContextResolver());
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
    void scriptShouldReturnMsgIdAndResolveOperatorHeaders() throws Exception {
        String body = """
                {
                  "vin": "LSVWA234567890123",
                  "scriptId": "script-001",
                  "executionMode": "parallel",
                  "globalTimeoutMs": 30000,
                  "priority": 5,
                  "ecus": [
                    {
                      "ecuName": "BMS",
                      "ecuScope": ["BMS", "GW"],
                      "txId": "0x641",
                      "rxId": "0x642",
                      "steps": [
                        {"seqId": 1, "type": "raw_uds", "data": "22F190", "timeoutMs": 5000}
                      ]
                    },
                    {
                      "ecuName": "VCU",
                      "ecuScope": ["VCU"],
                      "txId": "0x7E0",
                      "rxId": "0x7E8",
                      "steps": [
                        {"seqId": 1, "type": "raw_uds", "data": "22F190", "timeoutMs": 5000}
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/cmd/script")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, "eng-12345")
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, "chery-hq")
                        .header(OperatorContextResolver.HDR_TICKET_ID, "DIAG-2026-0429-001")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.msgId").value("550e8400-e29b-41d4-a716-446655440099"));

        verify(scriptCmdService).dispatch(any(ScriptCmdController.ScriptCmdRequest.class),
                eq(new Operator("eng-12345", OperatorRole.ENGINEER, "chery-hq", "DIAG-2026-0429-001")));
    }
}
