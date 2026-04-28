package com.opendota.odx.service;

import com.opendota.common.payload.common.EcuScope;
import com.opendota.odx.entity.OdxDiagService;
import com.opendota.odx.entity.OdxEcu;
import com.opendota.odx.entity.OdxParamCodec;
import com.opendota.odx.entity.OdxVehicleModel;
import com.opendota.odx.repository.OdxEcuRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ODX 服务目录与 ecuScope 推导(Phase 2 Step 2.2)。
 *
 * <p>本服务承担两件事:
 * <ol>
 *   <li>{@link #getServiceCatalog(long)}:按 {@code ecuId} 返回分组的服务目录树(协议 §13.4.2),
 *       前端 ECU 选中后用作指令面板的数据源。</li>
 *   <li>{@link #resolveEcuScope(String, String)}:开通道前推导完整 ecuScope(REST §4.1.1),
 *       例如 BMS → ["BMS", "GW"](BMS 经 GW 网关路由,锁定时必须同时锁 GW)。</li>
 * </ol>
 *
 * <p>所有异常路径(VIN/ECU 不存在)抛 {@link OdxResourceNotFoundException},由
 * GlobalExceptionHandler 统一映射为 REST {@code 40401}。
 */
@Service
public class OdxService {

    private final OdxEcuRepository repository;

    public OdxService(OdxEcuRepository repository) {
        this.repository = repository;
    }

    /**
     * 按 {@code ecuId} 构建服务目录(协议 §13.4.2)。
     *
     * <p>分组算法:Repository 已按 {@code (category ASC, id ASC)} 排序;此处保留遍历顺序,
     * 用 {@link LinkedHashMap} 即可得到稳定的 category 分组。空 category 不会出现(NOT NULL)。
     *
     * @throws OdxResourceNotFoundException 当 {@code ecuId} 不存在
     */
    public EcuServiceCatalog getServiceCatalog(long ecuId) {
        OdxEcu ecu = repository.findEcuById(ecuId)
                .orElseThrow(() -> new OdxResourceNotFoundException("ecu 不存在: id=" + ecuId));

        List<OdxDiagService> services = repository.findServicesByEcuId(ecuId);

        Map<String, List<EcuServiceCatalog.ServiceItem>> grouped = new LinkedHashMap<>();
        for (OdxDiagService s : services) {
            grouped.computeIfAbsent(s.category(), k -> new ArrayList<>())
                    .add(new EcuServiceCatalog.ServiceItem(
                            s.id(),
                            s.displayName(),
                            s.serviceCode(),
                            s.subFunction(),
                            s.requestRawHex(),
                            s.responseIdHex(),
                            s.macroType(),
                            s.requiredSession(),
                            s.requiredSecLevel(),
                            s.requiresSecurity(),
                            s.safetyCritical()));
        }

        List<EcuServiceCatalog.Category> categories = new ArrayList<>(grouped.size());
        grouped.forEach((cat, items) -> categories.add(new EcuServiceCatalog.Category(cat, items)));

        return new EcuServiceCatalog(
                ecu.id(),
                ecu.ecuName(),
                ecu.txId(),
                ecu.rxId(),
                ecu.protocol(),
                categories);
    }

    /**
     * 推导 ECU 作用域(REST §4.1.1)。
     *
     * <p>算法:
     * <ol>
     *   <li>VIN → 车型(单租户单车型 mock 简化,见 Repository 注释)</li>
     *   <li>(modelId, ecuName) → ECU,读 {@code gateway_chain}</li>
     *   <li>{@code ecuScope = normalize([requested] ∪ gateway_chain)}(字典序,协议 §10.2.3)</li>
     *   <li>reason 文本依据 gateway_chain 是否为空生成</li>
     * </ol>
     *
     * @throws OdxResourceNotFoundException 当 VIN 或 ECU 不存在
     */
    public EcuScopeResolution resolveEcuScope(String vin, String ecuName) {
        OdxVehicleModel model = repository.findVehicleModelByVin(vin)
                .orElseThrow(() -> new OdxResourceNotFoundException(
                        "vin 未关联到任何车型 ODX: " + vin));

        OdxEcu ecu = repository.findEcuByName(model.id(), ecuName)
                .orElseThrow(() -> new OdxResourceNotFoundException(
                        "ecu '" + ecuName + "' 不在车型 " + model.modelCode() + " 的 ODX 定义中"));

        List<String> chain = ecu.gatewayChain();
        List<String> merged = new ArrayList<>(chain.size() + 1);
        merged.add(ecuName);
        merged.addAll(chain);
        List<String> ecuScope = EcuScope.normalize(merged);

        String reason;
        if (chain.isEmpty()) {
            reason = ecuName + " 直连 OBD,无网关依赖";
        } else {
            reason = ecuName + " 经 " + String.join(" / ", chain)
                    + " 网关路由;锁 " + ecuName + " 必须同时锁 " + String.join(" / ", chain);
        }

        return new EcuScopeResolution(vin, model.modelCode(), ecuName, ecuScope, reason);
    }

    /**
     * 翻译单步诊断响应(协议 §13.7)。
     *
     * <p>MVP 先覆盖 Step 2.3 必需的两类路径:
     * <ul>
     *   <li>肯定响应:按 {@code requestRawHex} → ODX 服务 → {@code odx_param_codec} 解码</li>
     *   <li>否定响应:按 NRC 码表返回可读错误原因</li>
     * </ul>
     *
     * <p>匹配策略使用"最长 requestRawHex 前缀优先",兼容带动态参数尾巴的写入类请求。
     */
    public Map<String, Object> translateSingleResponse(String vin, String ecuName,
                                                       String requestRawHex, String responseRawHex) {
        String normalizedReq = normalizeHex(requestRawHex);
        String normalizedResp = normalizeHex(responseRawHex);

        if (normalizedResp == null || normalizedResp.isBlank()) {
            return Map.of(
                    "translationType", "empty",
                    "rawRequest", normalizedReq,
                    "rawResponse", normalizedResp,
                    "summaryText", "ECU 未返回响应数据");
        }

        OdxVehicleModel model = repository.findVehicleModelByVin(vin)
                .orElseThrow(() -> new OdxResourceNotFoundException(
                        "vin 未关联到任何车型 ODX: " + vin));
        OdxEcu ecu = repository.findEcuByName(model.id(), ecuName)
                .orElseThrow(() -> new OdxResourceNotFoundException(
                        "ecu '" + ecuName + "' 不在车型 " + model.modelCode() + " 的 ODX 定义中"));
        OdxDiagService service = findServiceByRequestHex(ecu.id(), normalizedReq)
                .orElseGet(() -> unknownService(ecu.id(), normalizedReq, normalizedResp));

        if (normalizedResp.startsWith("7F")) {
            return translateNegative(service, normalizedReq, normalizedResp);
        }
        return translatePositive(service, normalizedReq, normalizedResp);
    }

    private OdxDiagService unknownService(long ecuId, String requestRawHex, String responseRawHex) {
        String serviceCode = requestRawHex != null && requestRawHex.length() >= 2
                ? "0x" + requestRawHex.substring(0, 2)
                : responseRawHex != null && responseRawHex.length() >= 2
                ? "0x" + responseRawHex.substring(0, 2)
                : "UNKNOWN";
        String subFunction = requestRawHex != null && requestRawHex.length() > 2
                ? requestRawHex.substring(2)
                : null;
        return new OdxDiagService(
                -1L, ecuId, serviceCode, subFunction, "UnknownService", "未知诊断服务",
                null, "未知", requestRawHex, null, false, null, null, "raw_uds", true, false);
    }

    private Optional<OdxDiagService> findServiceByRequestHex(long ecuId, String requestRawHex) {
        if (requestRawHex == null || requestRawHex.isBlank()) {
            return Optional.empty();
        }
        return repository.findServicesByEcuId(ecuId).stream()
                .filter(service -> requestRawHex.startsWith(normalizeHex(service.requestRawHex())))
                .max((left, right) -> Integer.compare(
                        normalizeHex(left.requestRawHex()).length(),
                        normalizeHex(right.requestRawHex()).length()));
    }

    private Map<String, Object> translatePositive(OdxDiagService service, String requestRawHex, String responseRawHex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("translationType", "positive");
        result.put("serviceCode", service.serviceCode());
        result.put("serviceDisplayName", service.displayName());
        result.put("subFunction", service.subFunction());
        result.put("rawRequest", requestRawHex);
        result.put("rawResponse", responseRawHex);

        List<Map<String, Object>> parameters = new ArrayList<>();
        for (OdxParamCodec codec : repository.findParamCodecsByServiceId(service.id())) {
            parameters.add(decodeParameter(codec, responseRawHex));
        }
        result.put("parameters", parameters);
        result.put("summaryText", summaryTextForPositive(service, parameters));
        return result;
    }

    private Map<String, Object> translateNegative(OdxDiagService service, String requestRawHex, String responseRawHex) {
        String nrcHex = responseRawHex.length() >= 6 ? responseRawHex.substring(4, 6) : "10";
        Map<String, String> nrc = nrcInfo(nrcHex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("translationType", "negative");
        result.put("serviceCode", service.serviceCode());
        result.put("serviceDisplayName", service.displayName());
        result.put("subFunction", service.subFunction());
        result.put("rawRequest", requestRawHex);
        result.put("rawResponse", responseRawHex);
        result.put("nrcCode", "0x" + nrcHex);
        result.put("nrcName", nrc.get("name"));
        result.put("nrcDisplayName", nrc.get("display"));
        result.put("nrcDescription", nrc.get("description"));
        result.put("summaryText", service.displayName() + " 失败: " + nrc.get("display"));
        return result;
    }

    private Map<String, Object> decodeParameter(OdxParamCodec codec, String responseRawHex) {
        ExtractedBits extracted = extractBits(responseRawHex, codec.byteOffset(), codec.bitOffset(), codec.bitLength());

        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("paramName", codec.paramName());
        parameter.put("displayName", codec.displayName());
        parameter.put("rawHex", extracted.rawHex());
        parameter.put("rawDecimal", extracted.rawValue());

        if ("ascii".equalsIgnoreCase(codec.dataType())) {
            parameter.put("textValue", new String(extracted.rawBytes()));
        } else {
            BigDecimal physicalValue = applyFormula(BigDecimal.valueOf(extracted.rawValue()), codec.formula());
            parameter.put("physicalValue", physicalValue.stripTrailingZeros());
        }
        parameter.put("unit", codec.unit());
        parameter.put("formula", codec.formula());
        if (codec.minValue() != null || codec.maxValue() != null) {
            parameter.put("validRange", "[%s, %s]".formatted(
                    codec.minValue() == null ? "-inf" : codec.minValue().stripTrailingZeros().toPlainString(),
                    codec.maxValue() == null ? "+inf" : codec.maxValue().stripTrailingZeros().toPlainString()));
        }
        return parameter;
    }

    private static ExtractedBits extractBits(String responseRawHex, Integer byteOffset,
                                             Integer bitOffset, Integer bitLength) {
        byte[] bytes = hexToBytes(responseRawHex);
        int offsetBytes = byteOffset == null ? 0 : byteOffset;
        int offsetBits = bitOffset == null ? 0 : bitOffset;
        int lengthBits = bitLength == null ? 8 : bitLength;
        int byteCount = Math.max(1, (offsetBits + lengthBits + 7) / 8);
        if (offsetBytes < 0 || offsetBytes + byteCount > bytes.length) {
            return new ExtractedBits("", 0L, new byte[0]);
        }

        byte[] window = Arrays.copyOfRange(bytes, offsetBytes, offsetBytes + byteCount);
        BigInteger raw = new BigInteger(1, window);
        int rightShift = byteCount * 8 - offsetBits - lengthBits;
        if (rightShift > 0) {
            raw = raw.shiftRight(rightShift);
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(lengthBits).subtract(BigInteger.ONE);
        raw = raw.and(mask);

        byte[] normalized = raw.toByteArray();
        if (normalized.length > 1 && normalized[0] == 0) {
            normalized = Arrays.copyOfRange(normalized, 1, normalized.length);
        }
        return new ExtractedBits(raw.toString(16).toUpperCase(), raw.longValue(), normalized);
    }

    private static BigDecimal applyFormula(BigDecimal rawValue, String formula) {
        if (formula == null || formula.isBlank()) {
            return rawValue;
        }

        List<String> tokens = new ArrayList<>(Arrays.asList(formula.trim().split("\\s+")));
        if (tokens.isEmpty()) {
            return rawValue;
        }
        if (!"raw".equalsIgnoreCase(tokens.get(0))) {
            return rawValue;
        }

        BigDecimal result = rawValue;
        for (int i = 1; i + 1 < tokens.size(); i += 2) {
            String operator = tokens.get(i);
            BigDecimal operand = new BigDecimal(tokens.get(i + 1));
            result = switch (operator) {
                case "*" -> result.multiply(operand);
                case "/" -> operand.signum() == 0
                        ? result
                        : result.divide(operand, 8, RoundingMode.HALF_UP);
                case "+" -> result.add(operand);
                case "-" -> result.subtract(operand);
                default -> result;
            };
        }
        return result;
    }

    private static String summaryTextForPositive(OdxDiagService service, List<Map<String, Object>> parameters) {
        if (parameters.isEmpty()) {
            return service.displayName() + " 成功";
        }
        Map<String, Object> first = parameters.get(0);
        String unit = first.get("unit") == null ? "" : first.get("unit").toString();
        Object physicalValue = first.get("physicalValue");
        if (physicalValue != null) {
            return "%s: %s=%s%s".formatted(
                    service.displayName(),
                    first.get("displayName"),
                    physicalValue,
                    unit);
        }
        Object textValue = first.get("textValue");
        if (textValue != null) {
            return "%s: %s=%s".formatted(service.displayName(), first.get("displayName"), textValue);
        }
        return service.displayName() + " 成功";
    }

    private static Map<String, String> nrcInfo(String nrcHex) {
        return switch (nrcHex.toUpperCase()) {
            case "10" -> Map.of(
                    "name", "GeneralReject",
                    "display", "通用拒绝",
                    "description", "ECU 拒绝执行该服务,但未给出更具体原因。");
            case "22" -> Map.of(
                    "name", "ConditionsNotCorrect",
                    "display", "当前条件不满足",
                    "description", "ECU 当前状态不满足该服务执行条件,通常需要切会话或先解锁。");
            case "31" -> Map.of(
                    "name", "RequestOutOfRange",
                    "display", "请求超出允许范围",
                    "description", "请求的 DID / 参数值不在 ECU 允许的范围内。");
            case "33" -> Map.of(
                    "name", "SecurityAccessDenied",
                    "display", "安全访问被拒绝",
                    "description", "当前 ECU 未完成所需安全解锁,拒绝执行高权限服务。");
            case "35" -> Map.of(
                    "name", "InvalidKey",
                    "display", "密钥无效",
                    "description", "安全访问密钥校验失败,请重新获取 seed 后重试。");
            default -> Map.of(
                    "name", "UnknownNrc",
                    "display", "未知否定应答",
                    "description", "ECU 返回了未在当前码表中的 NRC: 0x" + nrcHex.toUpperCase());
        };
    }

    private static byte[] hexToBytes(String hex) {
        String normalized = normalizeHex(hex);
        if (normalized == null || normalized.isBlank()) {
            return new byte[0];
        }
        int evenLength = normalized.length() % 2 == 0 ? normalized.length() : normalized.length() - 1;
        byte[] bytes = new byte[evenLength / 2];
        for (int i = 0; i < evenLength; i += 2) {
            bytes[i / 2] = (byte) Integer.parseInt(normalized.substring(i, i + 2), 16);
        }
        return bytes;
    }

    private static String normalizeHex(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("0x", "")
                .replace("0X", "")
                .replaceAll("\\s+", "")
                .trim()
                .toUpperCase();
        return normalized.isEmpty() ? null : normalized;
    }

    private record ExtractedBits(String rawHex, long rawValue, byte[] rawBytes) {
    }
}
