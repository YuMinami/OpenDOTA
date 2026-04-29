package com.opendota.common.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiResponseTest {

    @Test
    void ok_createsSuccessResponse() {
        ApiResponse<String> resp = ApiResponse.ok("hello");
        assertEquals(0, resp.code());
        assertEquals("success", resp.msg());
        assertEquals("hello", resp.data());
    }

    @Test
    void error_withApiError() {
        ApiResponse<Object> resp = ApiResponse.error(ApiError.E40101);
        assertEquals(40101, resp.code());
        assertEquals("未登录", resp.msg());
        assertNull(resp.data());
    }

    @Test
    void error_withCustomMessage() {
        ApiResponse<Object> resp = ApiResponse.error(ApiError.E40001, "自定义消息");
        assertEquals(40001, resp.code());
        assertEquals("自定义消息", resp.msg());
    }

    @Test
    void error_blankMessage_fallsBackToDefault() {
        ApiResponse<Object> resp = ApiResponse.error(ApiError.E40001, "  ");
        assertEquals(40001, resp.code());
        assertEquals("参数缺失", resp.msg());
    }

    @Test
    void apiError_fromCode_returnsCorrect() {
        assertEquals(ApiError.E40101, ApiError.fromCode(40101));
        assertEquals(ApiError.SUCCESS, ApiError.fromCode(0));
    }

    @Test
    void apiError_fromCode_unknownCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> ApiError.fromCode(99999));
    }

    @Test
    void businessException_withError() {
        BusinessException ex = new BusinessException(ApiError.E40001);
        assertEquals(ApiError.E40001, ex.error());
        assertEquals(40001, ex.code());
    }

    @Test
    void businessException_withCustomMessage() {
        BusinessException ex = new BusinessException(ApiError.E40001, "custom");
        assertEquals("custom", ex.getMessage());
    }
}
