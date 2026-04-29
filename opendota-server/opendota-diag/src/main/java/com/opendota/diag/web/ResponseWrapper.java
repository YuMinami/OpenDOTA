package com.opendota.diag.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.opendota.common.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 成功响应自动包装器。SSE / 二进制响应透传,避免破坏流式协议。
 */
@RestControllerAdvice(basePackages = "com.opendota")
public class ResponseWrapper implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public ResponseWrapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (shouldBypass(body, selectedContentType)) {
            return body;
        }

        ApiResponse<?> wrapped = ApiResponse.ok(body);
        if (body instanceof String) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                return objectMapper.writeValueAsString(wrapped);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("ApiResponse 序列化失败", e);
            }
        }
        return wrapped;
    }

    private static boolean shouldBypass(Object body, MediaType selectedContentType) {
        if (body instanceof ApiResponse
                || body instanceof SseEmitter
                || body instanceof StreamingResponseBody
                || body instanceof Resource
                || body instanceof byte[]) {
            return true;
        }
        return selectedContentType != null && selectedContentType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM);
    }
}
