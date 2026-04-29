package com.opendota.diag.arbitration;

import com.opendota.common.web.ApiError;
import com.opendota.common.web.BusinessException;

import java.util.List;

/**
 * ECU 锁获取失败异常(协议 §10.2.3 + §10.3)。
 *
 * <p>当 {@link EcuLockRegistry#tryAcquireAll} 无法按字典序一次性获取所有 ECU 锁时抛出。
 * 携带 {@link #conflictEcus} 列表,与车端 {@code queue_reject { reason: "MULTI_ECU_LOCK_FAILED",
 * conflictEcus: [...] }} 对应。
 *
 * <p>继承 {@link BusinessException},由 {@code GlobalExceptionHandler} 统一映射为
 * {@code {code: 42307, msg: "...", data: null}} 响应。
 */
public class EcuLockException extends BusinessException {

    private final String vin;
    private final List<String> conflictEcus;

    /**
     * @param vin           目标车辆
     * @param conflictEcus  冲突的 ECU 列表(即获取失败的那些 ECU)
     * @param detailMessage 详细描述
     */
    public EcuLockException(String vin, List<String> conflictEcus, String detailMessage) {
        super(ApiError.E42307, detailMessage);
        this.vin = vin;
        this.conflictEcus = List.copyOf(conflictEcus);
    }

    /** 目标车辆 VIN。 */
    public String getVin() {
        return vin;
    }

    /** 冲突 ECU 列表(获取失败的 ECU)。 */
    public List<String> getConflictEcus() {
        return conflictEcus;
    }
}
