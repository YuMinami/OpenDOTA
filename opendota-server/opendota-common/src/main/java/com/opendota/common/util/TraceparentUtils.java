package com.opendota.common.util;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * W3C Trace Context {@code traceparent} 工具。
 */
public final class TraceparentUtils {

    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{2}-([0-9a-fA-F]{32})-[0-9a-fA-F]{16}-[0-9a-fA-F]{2}(?:-.*)?$");
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    private TraceparentUtils() {
    }

    public static Optional<String> extractTraceId(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TRACEPARENT_PATTERN.matcher(traceparent.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String traceId = matcher.group(1).toLowerCase(Locale.ROOT);
        if (INVALID_TRACE_ID.equals(traceId)) {
            return Optional.empty();
        }
        return Optional.of(traceId);
    }

    public static String extractOrGenerateTraceId(String traceparent) {
        return extractTraceId(traceparent).orElseGet(TraceparentUtils::generateTraceId);
    }

    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
