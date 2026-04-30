package com.opendota.task.revise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opendota.task.entity.TaskDispatchRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 任务 supersede 决策器(协议 §12.6.3)。
 *
 * <p>根据旧任务的分发状态与 payload 类型,三分支决策:
 * <ol>
 *   <li>{@link SupersedeAction#DIRECT_REPLACE} — 旧任务在队列中/暂停/未下发,直接取消旧任务、入队新任务</li>
 *   <li>{@link SupersedeAction#MARK_SUPERSEDING} — 旧任务正在执行常规 UDS/routine_wait,标记 superseding 等车端确认</li>
 *   <li>{@link SupersedeAction#REJECT} — 旧任务正在执行不可中断宏(macro_data_transfer/macro_security),拒绝修订</li>
 * </ol>
 */
@Component
public class SupersedeDecider {

    private static final Logger log = LoggerFactory.getLogger(SupersedeDecider.class);

    /** 不可中断的宏类型集合(协议 §12.6.3 第三分支)。 */
    private static final Set<String> NON_INTERRUPTIBLE_MACRO_TYPES = Set.of(
            "macro_data_transfer",
            "macro_security"
    );

    private final ObjectMapper objectMapper;

    public SupersedeDecider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 判断旧任务的某条 dispatch_record 应该如何被 supersede。
     *
     * @param oldRecord 旧任务的分发记录
     * @param oldDiagPayload 旧任务的 diagPayload JSON 字符串(用于判断宏类型)
     * @return 决策结果
     */
    public SupersedeDecision decide(TaskDispatchRecord oldRecord, String oldDiagPayload) {
        String status = oldRecord.getDispatchStatus();

        // 未下发 / 队列中 / 失败 → 直接替换
        if (isQueuedOrPending(status)) {
            log.debug("supersede 决策: vin={}, 旧状态={}, → DIRECT_REPLACE", oldRecord.getVin(), status);
            return new SupersedeDecision(SupersedeAction.DIRECT_REPLACE, null);
        }

        // 已完成 / 已取消 / 已过期 → 不需要 supersede(跳过)
        if (isTerminal(status)) {
            log.debug("supersede 决策: vin={}, 旧状态={} 已终态, 跳过", oldRecord.getVin(), status);
            return new SupersedeDecision(SupersedeAction.SKIP, null);
        }

        // dispatched 状态: 需要进一步判断是否正在执行不可中断宏
        if ("dispatched".equals(status)) {
            String macroType = extractExecutingMacroType(oldDiagPayload);
            if (macroType != null && NON_INTERRUPTIBLE_MACRO_TYPES.contains(macroType)) {
                log.info("supersede 决策: vin={}, 正在执行不可中断宏 {}, → REJECT",
                        oldRecord.getVin(), macroType);
                return new SupersedeDecision(SupersedeAction.REJECT, "NON_INTERRUPTIBLE");
            }
            log.debug("supersede 决策: vin={}, dispatched 常规任务, → MARK_SUPERSEDING", oldRecord.getVin());
            return new SupersedeDecision(SupersedeAction.MARK_SUPERSEDING, null);
        }

        // superseding 状态: 已经在被 supersede 中,跳过
        if ("superseding".equals(status)) {
            log.debug("supersede 决策: vin={}, 已在 superseding 状态, 跳过", oldRecord.getVin());
            return new SupersedeDecision(SupersedeAction.SKIP, null);
        }

        // 默认: 直接替换
        log.warn("supersede 决策: vin={}, 未知状态={}, 默认 DIRECT_REPLACE", oldRecord.getVin(), status);
        return new SupersedeDecision(SupersedeAction.DIRECT_REPLACE, null);
    }

    /**
     * 判断是否为队列/待下发状态。
     */
    private boolean isQueuedOrPending(String status) {
        return "pending_online".equals(status)
                || "queued".equals(status)
                || "failed".equals(status);
    }

    /**
     * 判断是否为终态。
     */
    private boolean isTerminal(String status) {
        return "completed".equals(status)
                || "canceled".equals(status)
                || "expired".equals(status);
    }

    /**
     * 从 diagPayload 中提取当前正在执行的宏类型。
     * 如果 payload 中包含 macroType 字段,返回该值;否则返回 null。
     */
    private String extractExecutingMacroType(String diagPayloadJson) {
        if (diagPayloadJson == null || diagPayloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(diagPayloadJson);
            // 尝试从 steps[0].macroType 或 macroType 提取
            JsonNode macroType = root.path("macroType");
            if (macroType.isTextual()) {
                return macroType.asText();
            }
            JsonNode steps = root.path("steps");
            if (steps.isArray() && !steps.isEmpty()) {
                JsonNode firstStepMacro = steps.get(0).path("macroType");
                if (firstStepMacro.isTextual()) {
                    return firstStepMacro.asText();
                }
            }
        } catch (Exception e) {
            log.warn("解析 diagPayload 宏类型失败", e);
        }
        return null;
    }

    /**
     * Supersede 决策结果。
     */
    public record SupersedeDecision(SupersedeAction action, String rejectReason) {}

    /**
     * Supersede 动作枚举。
     */
    public enum SupersedeAction {
        /** 直接替换:旧任务未在执行,直接取消旧任务、入队新任务 */
        DIRECT_REPLACE,
        /** 标记 superseding:旧任务正在执行常规操作,等待车端完成后确认 */
        MARK_SUPERSEDING,
        /** 拒绝:旧任务正在执行不可中断宏,无法 supersede */
        REJECT,
        /** 跳过:旧任务已终态,无需处理 */
        SKIP
    }
}
