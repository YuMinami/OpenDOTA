package com.opendota.odx.service;

import com.opendota.common.payload.common.EcuScope;
import com.opendota.odx.entity.OdxDiagService;
import com.opendota.odx.entity.OdxEcu;
import com.opendota.odx.entity.OdxVehicleModel;
import com.opendota.odx.repository.OdxEcuRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}
