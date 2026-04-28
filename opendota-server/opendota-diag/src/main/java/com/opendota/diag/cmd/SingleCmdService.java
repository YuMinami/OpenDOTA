package com.opendota.diag.cmd;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.Operator;
import com.opendota.common.logging.DiagMdcKeys;
import com.opendota.common.payload.single.SingleCmdPayload;
import com.opendota.diag.dispatch.DiagDispatcher;
import com.opendota.diag.web.ApiError;
import com.opendota.diag.web.BusinessException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 单步诊断下发服务。
 *
 * <p>顺序固定为:
 * <ol>
 *   <li>校验请求</li>
 *   <li>构造 C2V envelope</li>
 *   <li>写 pending {@code diag_record}</li>
 *   <li>发 MQTT;若失败则回滚本次 pending 记录</li>
 * </ol>
 */
@Service
public class SingleCmdService {

    private final DiagDispatcher dispatcher;
    private final DiagRecordRepository diagRecordRepository;

    public SingleCmdService(DiagDispatcher dispatcher, DiagRecordRepository diagRecordRepository) {
        this.dispatcher = dispatcher;
        this.diagRecordRepository = diagRecordRepository;
    }

    public String dispatch(SingleCmdController.SingleCmdRequest request, Operator operator) {
        validate(request);

        DiagDispatcher.PreparedSingleCmd prepared = dispatcher.prepareSingleCmd(
                request.channelId(), request.type(), request.reqData(), request.timeoutMs(), operator);
        SingleCmdPayload payload = prepared.envelope().payload();

        diagRecordRepository.insertPendingSingleCmd(new DiagRecordRepository.PendingSingleCmd(
                prepared.envelope().msgId(),
                operator.tenantId(),
                prepared.channelContext().getVin(),
                prepared.channelContext().getEcuName(),
                DiagAction.SINGLE_CMD.wireName(),
                payload.reqData(),
                operator.id(),
                operator.role() == null ? null : operator.role().wireName(),
                operator.ticketId(),
                currentTraceId()));

        try {
            dispatcher.publishPreparedSingleCmd(prepared);
            return prepared.envelope().msgId();
        } catch (RuntimeException ex) {
            diagRecordRepository.deleteByMsgId(prepared.envelope().msgId());
            throw ex;
        }
    }

    private static void validate(SingleCmdController.SingleCmdRequest req) {
        if (req == null) {
            throw new BusinessException(ApiError.E40001, "请求 body 必填");
        }
        if (req.channelId() == null || req.channelId().isBlank()) {
            throw new BusinessException(ApiError.E40001, "channelId 必填");
        }
        if (req.reqData() == null || req.reqData().isBlank()) {
            throw new BusinessException(ApiError.E40001, "reqData 必填(UDS PDU hex)");
        }
    }

    private static String currentTraceId() {
        String traceId = MDC.get(DiagMdcKeys.TRACE_ID);
        return DiagMdcKeys.NO_VALUE.equals(traceId) ? null : traceId;
    }
}
