## 关联 Phase / Step

Phase X Step Y.Z  <!-- 参考 doc/opendota_development_plan.md -->

## 变更摘要

- 做了什么
- 为什么(业务/技术原因)

## 影响面

- [ ] 协议变更(需 bump `dota/vN` 吗)
- [ ] DB schema 变更(新增 Flyway `V{n}__xxx.sql`)
- [ ] 向后兼容(是否破坏已有调用方)
- [ ] MQTT topic 新增/变更
- [ ] Kafka topic 新增/变更
- [ ] 新增 `act` / `dispatch_status` / error code(三份 spec 同步)

## 验收证据

- [ ] 单元测试 `XxxTest` 通过(新增 N 个)
- [ ] 集成测试场景 X.Y 通过
- [ ] 符合性用例 TV-N-M 通过(若适用)
- [ ] 手动验证: `cd opendota-server && mvn spring-boot:run -f opendota-app/pom.xml -Dspring-boot.run.profiles=dev` + `curl ...` 返回 ✅

## 文档更新

- [ ] 协议 §X.Y
- [ ] 架构 §Z.W
- [ ] REST §A.B
- [ ] 车端规范 §C.D
- [ ] 本开发计划 Step 标记完成

## 运维注意

- [ ] 新增 ENV 变量
- [ ] 新增 Prometheus 指标
- [ ] 新增告警规则
- [ ] 新增 RLS / 审计 字段

## 审阅清单 (参考 §3.4)

- [ ] 无魔法字符串(act/status 走 enum)
- [ ] MDC 五字段在所有异步边界注入(traceId/msgId/vin/operatorId/tenantId)
- [ ] PG 写操作全部在 `@Transactional` 内
- [ ] 外部 IO 走 Outbox 或明确容错分支
- [ ] RLS 不可绕过
- [ ] 中文注释保持(架构 §11.1)
