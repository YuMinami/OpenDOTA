package com.opendota.diag.web;

import com.opendota.mqtt.publisher.MqttPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * REST 全局异常处理。把业务异常和常见请求异常统一映射为 {@code {code,msg,data:null}}。
 */
@RestControllerAdvice(basePackages = "com.opendota")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException ex) {
        log.warn("业务异常 code={} msg={}", ex.code(), ex.getMessage());
        return ResponseEntity.ok(ApiResponse.error(ex.error(), ex.getMessage()));
    }

    @ExceptionHandler(MqttPublishException.class)
    public ResponseEntity<ApiResponse<Object>> handleMqtt(MqttPublishException ex) {
        log.error("MQTT 下游异常: {}", ex.getMessage(), ex);
        return ResponseEntity.ok(ApiResponse.error(ApiError.E50301, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        return handleBindingResult(ApiError.E40001, ex.getBindingResult(), "参数校验失败");
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Object>> handleBind(BindException ex) {
        return handleBindingResult(ApiError.E40002, ex.getBindingResult(), "参数绑定失败");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        String detail = "缺少请求参数: " + ex.getParameterName();
        log.warn("请求参数缺失: {}", detail);
        return ResponseEntity.ok(ApiResponse.error(ApiError.E40001, detail));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        String detail = "缺少请求头: " + ex.getHeaderName();
        log.warn("请求头缺失: {}", detail);
        return ResponseEntity.ok(ApiResponse.error(ApiError.E40001, detail));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("请求体格式错误: {}", ex.getMessage());
        return ResponseEntity.ok(ApiResponse.error(ApiError.E40002, "请求体格式错误"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "参数 %s 格式错误".formatted(ex.getName());
        log.warn("参数类型错误: {}", detail);
        return ResponseEntity.ok(ApiResponse.error(ApiError.E40002, detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("非法参数: {}", ex.getMessage());
        return ResponseEntity.ok(ApiResponse.error(ApiError.E40002, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception ex) {
        log.error("未分类异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ApiError.E50001));
    }

    private ResponseEntity<ApiResponse<Object>> handleBindingResult(ApiError error,
                                                                   BindingResult bindingResult,
                                                                   String fallbackMessage) {
        String detail = bindingResult.getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .reduce((left, right) -> left + "; " + right)
                .orElse(fallbackMessage);
        log.warn("请求参数校验失败 code={} msg={}", error.code(), detail);
        return ResponseEntity.ok(ApiResponse.error(error, detail));
    }
}
