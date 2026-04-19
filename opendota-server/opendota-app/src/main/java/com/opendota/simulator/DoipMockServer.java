package com.opendota.simulator;

import com.opendota.common.util.HexUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DoIP 模拟服务器(架构 §8.5.1 + v1.4 B1)。
 *
 * <p>最小实现 ISO 13400-2 的核心 payload type:
 * <ul>
 *   <li>{@code 0x0005} Routing Activation Request → {@code 0x0006} Response (code=0x10 成功)</li>
 *   <li>{@code 0x8001} Diagnostic Message → {@code 0x8002} Ack + {@code 0x8001} Response(查 CanMockResponder)</li>
 *   <li>{@code 0x0007} Alive Check Request → {@code 0x0008} Response</li>
 * </ul>
 *
 * <p>TCP 连接隔离 = workstation 隔离(架构 §8.5.2)。每个 accept 后开虚拟线程独立处理,
 * 多个工位可并行连接同一模拟器。
 */
public class DoipMockServer {

    private static final Logger log = LoggerFactory.getLogger(DoipMockServer.class);

    /** DoIP Generic Header 固定长度 8 字节。 */
    private static final int HEADER_LEN = 8;

    private final int port;
    private final CanMockResponder canResponder;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public DoipMockServer(int port, CanMockResponder canResponder) {
        this.port = port;
        this.canResponder = canResponder;
    }

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("doip-mock-accept").start(this::acceptLoop);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        try (ServerSocket ss = new ServerSocket(port)) {
            serverSocket = ss;
            log.info("🔌 DoIP Mock Server 启动: port={}", port);
            while (running.get() && !ss.isClosed()) {
                Socket client = ss.accept();
                Thread.ofVirtual().name("doip-client-" + client.getRemoteSocketAddress())
                        .start(() -> handleClient(client));
            }
        } catch (SocketException e) {
            if (running.get()) log.error("DoIP server accept 异常", e);
        } catch (IOException e) {
            log.error("DoIP server 绑定失败 port={}", port, e);
        }
    }

    private void handleClient(Socket client) {
        log.info("DoIP client 接入: {}", client.getRemoteSocketAddress());
        try (client;
             DataInputStream in = new DataInputStream(client.getInputStream());
             DataOutputStream out = new DataOutputStream(client.getOutputStream())) {

            while (!client.isClosed()) {
                // 读 DoIP Generic Header (8 bytes)
                byte protoVer = in.readByte();
                byte invProtoVer = in.readByte();
                short payloadType = in.readShort();
                int payloadLen = in.readInt();

                // 兼容 ISO 13400-2:2019 的 protocolVersion=0x02,以及 inverse=0xFD
                if ((byte) ~protoVer != invProtoVer) {
                    log.warn("DoIP header 校验失败 proto=0x{} inv=0x{}",
                            Integer.toHexString(protoVer & 0xFF),
                            Integer.toHexString(invProtoVer & 0xFF));
                    return;
                }

                byte[] payload = in.readNBytes(payloadLen);
                if (payload.length != payloadLen) {
                    log.warn("DoIP payload 不完整 expected={} got={}", payloadLen, payload.length);
                    return;
                }

                handlePayload(payloadType, payload, out, protoVer);
            }
        } catch (IOException e) {
            // 正常 TCP 断开,记 INFO
            log.info("DoIP client 断开: {} ({})", client.getRemoteSocketAddress(), e.getMessage());
        }
    }

    private void handlePayload(short payloadType, byte[] payload, DataOutputStream out, byte protoVer)
            throws IOException {
        switch (payloadType) {
            case 0x0005 -> handleRoutingActivation(payload, out, protoVer);
            case 0x0007 -> handleAliveCheck(out, protoVer);
            case (short) 0x8001 -> handleDiagnosticMessage(payload, out, protoVer);
            default -> log.warn("未实现的 DoIP payload type: 0x{}", Integer.toHexString(payloadType & 0xFFFF));
        }
    }

    private void handleRoutingActivation(byte[] req, DataOutputStream out, byte protoVer) throws IOException {
        // Request: SA(2) + ActType(1) + Reserved(4)  = 7 bytes(v2)
        if (req.length < 7) {
            log.warn("Routing Activation 请求长度异常: {}", req.length);
            return;
        }
        int sa = ((req[0] & 0xFF) << 8) | (req[1] & 0xFF);

        // Response: SA(2) + TA(2) + Code(1) + Reserved(4)  = 9 bytes(v2)
        byte[] resp = new byte[9];
        resp[0] = req[0]; resp[1] = req[1];                 // SA
        resp[2] = 0x10; resp[3] = 0x01;                     // TA = 0x1001 (模拟 ECU 地址)
        resp[4] = 0x10;                                      // Code = 0x10 Success
        // Reserved 0x00000000

        writeDoipFrame(out, protoVer, (short) 0x0006, resp);
        log.debug("DoIP Routing Activation 成功: SA=0x{}", Integer.toHexString(sa));
    }

    private void handleAliveCheck(DataOutputStream out, byte protoVer) throws IOException {
        // Alive Check Response: SA(2) = 模拟返回 0x1001
        writeDoipFrame(out, protoVer, (short) 0x0008, new byte[]{0x10, 0x01});
    }

    private void handleDiagnosticMessage(byte[] req, DataOutputStream out, byte protoVer) throws IOException {
        // Diagnostic Message: SA(2) + TA(2) + UserData(n)
        if (req.length < 4) return;
        byte[] sa = {req[0], req[1]};
        byte[] ta = {req[2], req[3]};
        byte[] udsPdu = new byte[req.length - 4];
        System.arraycopy(req, 4, udsPdu, 0, udsPdu.length);
        String reqHex = HexUtils.toHex(udsPdu);

        // 先发 Ack (0x8002): SA + TA + AckCode(0x00)
        byte[] ack = new byte[5];
        ack[0] = ta[0]; ack[1] = ta[1];  // 回:SA=ECU TA
        ack[2] = sa[0]; ack[3] = sa[1];  // 回:TA=Tester SA
        ack[4] = 0x00;                    // Ack Code 0x00 OK
        writeDoipFrame(out, protoVer, (short) 0x8002, ack);

        // 若是 3E 80 TesterPresent 抑制响应,不继续发业务响应
        if (canResponder.shouldSuppressResponse(reqHex)) {
            return;
        }

        // 再发业务响应 (0x8001): SA + TA + UDS Resp
        String respHex = canResponder.respond(reqHex);
        byte[] respPdu = HexUtils.fromHex(respHex);
        byte[] respFrame = new byte[4 + respPdu.length];
        respFrame[0] = ta[0]; respFrame[1] = ta[1];
        respFrame[2] = sa[0]; respFrame[3] = sa[1];
        System.arraycopy(respPdu, 0, respFrame, 4, respPdu.length);
        writeDoipFrame(out, protoVer, (short) 0x8001, respFrame);

        log.debug("DoIP 诊断 req={} resp={}", reqHex, respHex);
    }

    private void writeDoipFrame(DataOutputStream out, byte protoVer, short payloadType, byte[] payload)
            throws IOException {
        out.writeByte(protoVer);
        out.writeByte((byte) ~protoVer);
        out.writeShort(payloadType);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }
}
