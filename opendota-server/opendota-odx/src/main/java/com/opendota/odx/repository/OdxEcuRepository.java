package com.opendota.odx.repository;

import com.opendota.odx.entity.OdxDiagService;
import com.opendota.odx.entity.OdxEcu;
import com.opendota.odx.entity.OdxParamCodec;
import com.opendota.odx.entity.OdxVehicleModel;

import java.util.List;
import java.util.Optional;

/**
 * ODX 元数据访问层契约(Phase 2 Step 2.2)。
 *
 * <p>本接口集中暴露服务目录与 ecuScope 推导所需的查询。面向接口编程也让 Service 层
 * 与具体存储引擎(目前是 JdbcTemplate)解耦,便于单元测试与未来替换存储后端。
 *
 * <p>实现见 {@link OdxEcuRepositoryImpl}。
 */
public interface OdxEcuRepository {

    Optional<OdxVehicleModel> findVehicleModelById(long modelId);

    /**
     * VIN → 车型映射(MVP 简化版)。
     *
     * <p>Phase 2 之前未建专门的 vehicle 主表;此处经
     * {@code vehicle_online_status.tenant_id} 推到该租户下的车型(mock 单租户单车型)。
     * Phase 4 引入 vehicle 主表后用 {@code vehicle.model_id} 精准定位。
     */
    Optional<OdxVehicleModel> findVehicleModelByVin(String vin);

    Optional<OdxEcu> findEcuById(long ecuId);

    List<OdxEcu> findEcusByModelId(long modelId);

    /**
     * 按短名(如 {@code "BMS"})或全名(如 {@code "BMS 电池管理系统"})定位 ECU。
     *
     * <p>seed 中 ecu_name 通常前缀是规范短名,前端 ecuScope 字段也用短名;此处兼容两种写法。
     */
    Optional<OdxEcu> findEcuByName(long modelId, String ecuName);

    /** 按 ECU 列出所有诊断服务,实现层负责返回稳定排序(category ASC, id ASC)。 */
    List<OdxDiagService> findServicesByEcuId(long ecuId);

    /** 查询某个诊断服务的参数解码规则(按字节偏移稳定排序)。 */
    List<OdxParamCodec> findParamCodecsByServiceId(long serviceId);
}
