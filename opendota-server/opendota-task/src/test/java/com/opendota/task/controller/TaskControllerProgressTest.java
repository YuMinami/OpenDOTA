package com.opendota.task.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;
import com.opendota.diag.api.OperatorContextResolver;
import com.opendota.diag.web.GlobalExceptionHandler;
import com.opendota.diag.web.ResponseWrapper;
import com.opendota.task.progress.TaskProgressService;
import com.opendota.task.progress.TaskProgressService.TaskExecutionLogItem;
import com.opendota.task.progress.TaskProgressService.TaskProgressView;
import com.opendota.task.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TaskController 进度聚合端点 MockMvc 测试(REST §6.4 / §6.5)。
 *
 * <p>遵循 {@code SingleCmdControllerTest} 的 standalone 风格,不启动 Spring 容器。
 */
class TaskControllerProgressTest {

    private static final String TASK_ID = "tsk_abc123";
    private static final String TENANT = "chery-hq";
    private static final String OPERATOR_ID = "eng-12345";

    private MockMvc mockMvc;
    private TaskService taskService;
    private TaskProgressService progressService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        taskService = mock(TaskService.class);
        progressService = mock(TaskProgressService.class);
        TaskController controller = new TaskController(
                taskService, progressService, new OperatorContextResolver());
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
    void getProgress_returns11StatusFieldsAndBoardStatus() throws Exception {
        LinkedHashMap<String, Integer> distribution = new LinkedHashMap<>();
        distribution.put("pending_online", 1200);
        distribution.put("dispatched", 300);
        distribution.put("queued", 500);
        distribution.put("scheduling", 20);
        distribution.put("executing", 150);
        distribution.put("paused", 30);
        distribution.put("deferred", 20);
        distribution.put("completed", 2700);
        distribution.put("failed", 50);
        distribution.put("canceled", 10);
        distribution.put("expired", 40);

        when(progressService.getProgress(TASK_ID, TENANT)).thenReturn(new TaskProgressView(
                TASK_ID, "全车型 DTC 扫描", 2, "tsk_abc000", "active",
                5000, distribution, 0.54, "in_progress"));

        mockMvc.perform(get("/api/task/{taskId}/progress", TASK_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, OPERATOR_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(TASK_ID))
                .andExpect(jsonPath("$.data.totalTargets").value(5000))
                .andExpect(jsonPath("$.data.completionRate").value(0.54))
                .andExpect(jsonPath("$.data.boardStatus").value("in_progress"))
                .andExpect(jsonPath("$.data.progress.pending_online").value(1200))
                .andExpect(jsonPath("$.data.progress.completed").value(2700))
                .andExpect(jsonPath("$.data.progress.expired").value(40));
    }

    @Test
    void getProgress_returns404WhenTaskNotFoundOrCrossTenant() throws Exception {
        // 项目约定: GlobalExceptionHandler 把 BusinessException 包成 HTTP 200 +
        // ApiResponse{code=40401},不抛 4xx HTTP 状态。
        when(progressService.getProgress(TASK_ID, TENANT))
                .thenThrow(new BusinessException(ApiError.E40401, "任务不存在: " + TASK_ID));

        mockMvc.perform(get("/api/task/{taskId}/progress", TASK_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, OPERATOR_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40401))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void getExecutions_returnsPageWithDefaultParams() throws Exception {
        TaskExecutionLogItem item = new TaskExecutionLogItem(
                7, "LSVWA234567890123",
                1713300300000L, 0, 5200,
                1713300300120L, 1713300305320L,
                null, null);
        when(progressService.listExecutions(eq(TASK_ID), eq(TENANT), any(), eq(1), eq(50)))
                .thenReturn(new PageImpl<>(List.of(item), Pageable.ofSize(50), 1L));

        mockMvc.perform(get("/api/task/{taskId}/executions", TASK_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, OPERATOR_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.content[0].executionSeq").value(7))
                .andExpect(jsonPath("$.data.content[0].vin").value("LSVWA234567890123"))
                .andExpect(jsonPath("$.data.content[0].overallStatus").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getExecutions_passesVinAndPagingThrough() throws Exception {
        when(progressService.listExecutions(eq(TASK_ID), eq(TENANT), eq("LSVWA234567890123"), eq(2), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), Pageable.ofSize(20), 0L));

        mockMvc.perform(get("/api/task/{taskId}/executions", TASK_ID)
                        .param("vin", "LSVWA234567890123")
                        .param("page", "2")
                        .param("size", "20")
                        .header(OperatorContextResolver.HDR_OPERATOR_ID, OPERATOR_ID)
                        .header(OperatorContextResolver.HDR_OPERATOR_ROLE, "engineer")
                        .header(OperatorContextResolver.HDR_TENANT_ID, TENANT))
                .andExpect(status().isOk());

        verify(progressService).listExecutions(TASK_ID, TENANT, "LSVWA234567890123", 2, 20);
    }
}
