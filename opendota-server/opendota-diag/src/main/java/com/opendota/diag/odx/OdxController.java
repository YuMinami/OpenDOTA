package com.opendota.diag.odx;

import com.opendota.odx.service.EcuScopeResolution;
import com.opendota.odx.service.EcuServiceCatalog;
import com.opendota.odx.service.OdxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ODX 服务目录与 ECU 作用域查询(Phase 2 Step 2.2)。
 *
 * <p>本 Controller 把 {@link OdxService} 的两类查询暴露为 REST 端点:
 * <ul>
 *   <li>{@code GET /api/odx/services?ecuId=123} → ECU 服务目录(协议 §13.4.2)</li>
 *   <li>{@code GET /api/vehicle/{vin}/ecu-scope?ecuName=BMS} → 完整 ecuScope(REST §4.1.1)</li>
 * </ul>
 *
 * <p>不存在的 ecuId / VIN / ecuName 由 {@link com.opendota.odx.service.OdxResourceNotFoundException}
 * 抛出,GlobalExceptionHandler 统一映射 {@code 40401}。
 */
@RestController
@RequestMapping("/api")
public class OdxController {

    private final OdxService odxService;

    public OdxController(OdxService odxService) {
        this.odxService = odxService;
    }

    @GetMapping("/odx/services")
    public EcuServiceCatalog services(@RequestParam("ecuId") long ecuId) {
        return odxService.getServiceCatalog(ecuId);
    }

    @GetMapping("/vehicle/{vin}/ecu-scope")
    public EcuScopeResolution ecuScope(@PathVariable("vin") String vin,
                                       @RequestParam("ecuName") String ecuName) {
        return odxService.resolveEcuScope(vin, ecuName);
    }
}
