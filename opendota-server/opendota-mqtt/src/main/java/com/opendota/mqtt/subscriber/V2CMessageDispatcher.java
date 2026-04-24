package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * V2C handler 分派器。负责按 act 选路并隔离单个 handler 的异常。
 */
public class V2CMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(V2CMessageDispatcher.class);

    private final List<V2CHandler> handlers;

    public V2CMessageDispatcher(List<V2CHandler> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public void dispatch(String topic, DiagMessage<?> envelope) {
        boolean any = false;
        for (V2CHandler handler : handlers) {
            if (!handler.supports(envelope.act())) {
                continue;
            }
            any = true;
            try {
                handler.handle(topic, envelope);
            } catch (Exception e) {
                log.error("V2CHandler {} 处理异常 topic={} act={}",
                        handler.getClass().getSimpleName(), topic, envelope.act().wireName(), e);
            }
        }
        if (!any) {
            log.debug("V2C 无 handler 关心 topic={} act={}", topic, envelope.act().wireName());
        }
    }

    public int handlerCount() {
        return handlers.size();
    }
}
