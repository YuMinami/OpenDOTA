package com.opendota.diag.api;

import com.opendota.diag.exception.DispatchException;
import com.opendota.diag.exception.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * REST 全局异常处理。把业务异常映射为 {@code {code,msg,data}}。
 *
 * <p>规则:
 * <ul>
 *   <li>{@link DispatchException} → HTTP 200 + {code=业务码, msg=原因}</li>
 *   <li>参数校验失败 → HTTP 200 + {code=40001, msg=详情}(前端按 code 而非 HTTP status 判断)</li>
 *   <li>未分类 → HTTP 500 + {code=50000, msg="服务端异常"}</li>
 * </ul>
 *
 * 不用 HTTP 4xx/5xx status 的原因:前端 {@code axios} 拦截器按 {@code code} 统一分支
 * (CLAUDE.md 约定);让 2xx 带业务码,简化前端代码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DispatchException.class)
    public ResponseEntity<ApiResponse<Object>> handleDispatch(DispatchException ex) {
        log.warn("业务异常 code={} msg={}", ex.code(), ex.getMessage());
        return ResponseEntity.ok(ApiResponse.error(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.ok(ApiResponse.error(ErrorCodes.BAD_REQUEST, detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArg(IllegalArgumentException ex) {
        log.warn("参数错误: {}", ex.getMessage());
        return ResponseEntity.ok(ApiResponse.error(ErrorCodes.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnknown(Exception ex) {
        log.error("未分类异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(50000, "服务端异常: " + ex.getClass().getSimpleName()));
    }
}
