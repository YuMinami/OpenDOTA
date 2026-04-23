#!/usr/bin/env bash
# ============================================================
# Kafka topic 预创建脚本 (本地 docker-compose)
# ------------------------------------------------------------
# 参考: doc/opendota_deployment_spec.md §2.4 / 架构 §6.3
#
# 用法:
#   bash deploy/kafka-init.sh
#
# 前置: deploy/docker-compose.yml 中 kafka 容器已 healthy
# 幂等: 重复执行不会报错 (使用 --if-not-exists)
# ============================================================

set -euo pipefail

CONTAINER="${KAFKA_CONTAINER:-opendota-kafka}"
BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
# 本地 1 broker; 生产覆写为 3
REPLICATION="${KAFKA_REPLICATION:-1}"

# topic 清单: name:partitions
TOPICS=(
  "task-dispatch-queue:16"
  "vehicle-lifecycle:8"
  "task-dispatch-dlq:4"
)

echo "[kafka-init] 使用容器: ${CONTAINER}, bootstrap: ${BOOTSTRAP}, replication: ${REPLICATION}"

# 等待 kafka 就绪 (最长 60s)
for i in {1..12}; do
  if docker exec "${CONTAINER}" /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server "${BOOTSTRAP}" --list >/dev/null 2>&1; then
    echo "[kafka-init] Kafka 可用"
    break
  fi
  echo "[kafka-init] 等待 Kafka 就绪... (${i}/12)"
  sleep 5
done

for item in "${TOPICS[@]}"; do
  topic="${item%%:*}"
  partitions="${item##*:}"
  echo "[kafka-init] 创建 topic=${topic} partitions=${partitions}"
  docker exec "${CONTAINER}" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${topic}" \
    --partitions "${partitions}" \
    --replication-factor "${REPLICATION}"
done

echo "[kafka-init] 当前 topic 列表:"
docker exec "${CONTAINER}" /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server "${BOOTSTRAP}" --list

echo "[kafka-init] 完成"
