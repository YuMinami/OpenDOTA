package com.opendota.mqtt.subscriber;

import com.opendota.common.envelope.DiagAction;
import com.opendota.common.envelope.DiagMessage;
import com.opendota.common.payload.single.SingleRespPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class V2CMessageDispatcherTest {

    @Mock
    private V2CHandler matchingHandler;

    @Mock
    private V2CHandler ignoredHandler;

    @Mock
    private V2CHandler failingHandler;

    @Test
    void dispatchRoutesOnlyToSupportingHandlers() {
        DiagMessage<SingleRespPayload> envelope = envelope();
        when(matchingHandler.supports(DiagAction.SINGLE_RESP)).thenReturn(true);
        when(ignoredHandler.supports(DiagAction.SINGLE_RESP)).thenReturn(false);

        V2CMessageDispatcher dispatcher = new V2CMessageDispatcher(List.of(matchingHandler, ignoredHandler));
        dispatcher.dispatch("dota/v1/resp/single/LSVWA234567890123", envelope);

        verify(matchingHandler).handle("dota/v1/resp/single/LSVWA234567890123", envelope);
        verify(ignoredHandler, never()).handle("dota/v1/resp/single/LSVWA234567890123", envelope);
    }

    @Test
    void dispatchKeepsRunningWhenOneHandlerFails() {
        DiagMessage<SingleRespPayload> envelope = envelope();
        when(failingHandler.supports(DiagAction.SINGLE_RESP)).thenReturn(true);
        when(matchingHandler.supports(DiagAction.SINGLE_RESP)).thenReturn(true);
        doThrow(new IllegalStateException("boom")).when(failingHandler)
                .handle("dota/v1/resp/single/LSVWA234567890123", envelope);

        V2CMessageDispatcher dispatcher = new V2CMessageDispatcher(List.of(failingHandler, matchingHandler));
        dispatcher.dispatch("dota/v1/resp/single/LSVWA234567890123", envelope);

        verify(failingHandler).handle("dota/v1/resp/single/LSVWA234567890123", envelope);
        verify(matchingHandler).handle("dota/v1/resp/single/LSVWA234567890123", envelope);
    }

    private DiagMessage<SingleRespPayload> envelope() {
        return new DiagMessage<>(
                "msg-001",
                1713301200000L,
                "LSVWA234567890123",
                DiagAction.SINGLE_RESP,
                null,
                null,
                new SingleRespPayload("cmd-1", "ch-1", 0, null, "62F190", 18)
        );
    }
}
