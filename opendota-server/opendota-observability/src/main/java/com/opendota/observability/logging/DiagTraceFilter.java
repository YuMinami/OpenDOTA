package com.opendota.observability.logging;

import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.util.TraceparentUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 入口诊断追踪过滤器。
 *
 * <p>Phase 1 认证尚未接入,当前先从请求头恢复 operator / tenant / ticket,
 * 后续 JWT principal 落地后再切到认证对象提取。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiagTraceFilter extends OncePerRequestFilter {

    static final String HDR_TRACEPARENT = "traceparent";
    static final String HDR_OPERATOR_ID = "X-Operator-Id";
    static final String HDR_TENANT_ID = "X-Tenant-Id";
    static final String HDR_TICKET_ID = "X-Ticket-Id";

    private static final Logger log = LoggerFactory.getLogger(DiagTraceFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        HttpMdcContext context = resolveHttpContext(request);
        apply(context);
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 运行时链路中其它过滤器/观测组件可能改写或清空 MDC,这里在输出访问日志前恢复本请求上下文。
            apply(context);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.info("HTTP {} {} status={} durationMs={} remoteAddr={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs,
                    remoteAddrOf(request));
            restore(previous);
        }
    }

    private HttpMdcContext resolveHttpContext(HttpServletRequest request) {
        return new HttpMdcContext(
                TraceparentUtils.extractOrGenerateTraceId(request.getHeader(HDR_TRACEPARENT)),
                DiagMdcKeys.NO_VALUE,
                DiagMdcKeys.NO_VALUE,
                operatorIdOf(request),
                tenantIdOf(request),
                DiagMdcKeys.dashIfBlank(request.getHeader(HDR_TICKET_ID))
        );
    }

    private void apply(HttpMdcContext context) {
        MDC.put(DiagMdcKeys.TRACE_ID, context.traceId());
        MDC.put(DiagMdcKeys.MSG_ID, context.msgId());
        MDC.put(DiagMdcKeys.VIN, context.vin());
        MDC.put(DiagMdcKeys.OPERATOR_ID, context.operatorId());
        MDC.put(DiagMdcKeys.TENANT_ID, context.tenantId());
        MDC.put(DiagMdcKeys.TICKET_ID, context.ticketId());
    }

    private static String operatorIdOf(HttpServletRequest request) {
        String operatorId = request.getHeader(HDR_OPERATOR_ID);
        return DiagMdcKeys.NO_VALUE.equals(DiagMdcKeys.dashIfBlank(operatorId))
                ? DiagMdcKeys.SYSTEM_OPERATOR_ID
                : operatorId.trim();
    }

    private static String tenantIdOf(HttpServletRequest request) {
        String tenantId = request.getHeader(HDR_TENANT_ID);
        return DiagMdcKeys.NO_VALUE.equals(DiagMdcKeys.dashIfBlank(tenantId))
                ? DiagMdcKeys.DEFAULT_TENANT_ID
                : tenantId.trim();
    }

    private static String remoteAddrOf(HttpServletRequest request) {
        return DiagMdcKeys.dashIfBlank(request.getRemoteAddr());
    }

    private void restore(Map<String, String> previous) {
        MDC.clear();
        if (previous != null && !previous.isEmpty()) {
            MDC.setContextMap(previous);
        }
    }

    private record HttpMdcContext(
            String traceId,
            String msgId,
            String vin,
            String operatorId,
            String tenantId,
            String ticketId) {
    }
}
