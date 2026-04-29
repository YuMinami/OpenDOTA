#!/usr/bin/env bash
# ============================================================
# OpenDOTA Phase 2 单步诊断本地联调脚本
# ------------------------------------------------------------
# 目标:
#   1. 启动本地依赖(postgres / redis / emqx / kafka)
#   2. 启动 opendota-app(dev profile, 自动挂模拟器 + mock 数据)
#   3. 调用 /api/channel/open + /api/cmd/single
#   4. 实时订阅 /api/sse/subscribe/{vin} 并断言收到 diag-result
#   5. 追加校验 diag_record / sse_event 已写入
#
# 行为约定:
#   - Step 2.4 已落地后,实时 SSE 验证为主路径,不再接受 404/跳过降级
#   - 不主动关闭 docker compose,便于联调后继续观察容器状态
#
# 用法:
#   bash scripts/e2e/single-cmd-demo.sh
#
# 可选环境变量:
#   API_BASE_URL=http://127.0.0.1:8080
#   VIN=LSVWA234567890123
#   ECU_NAME=VCU
#   ECU_SCOPE_JSON='["VCU"]'
#   REQ_DATA=22F190
#   REQ_TIMEOUT_MS=5000
#   GLOBAL_TIMEOUT_MS=300000
#   BASIC_AUTH_USER=opendota
#   BASIC_AUTH_PASSWORD=opendota
#   TENANT_ID=default-tenant
#   REUSE_RUNNING_APP=1
#   INJECT_REDIS_FAILURE=1
# ============================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEPLOY_DIR="${ROOT_DIR}/deploy"
SERVER_DIR="${ROOT_DIR}/opendota-server"
COMPOSE_FILE="${DEPLOY_DIR}/docker-compose.yml"

API_BASE_URL="${API_BASE_URL:-http://127.0.0.1:8080}"
VIN="${VIN:-LSVWA234567890123}"
ECU_NAME="${ECU_NAME:-VCU}"
ECU_SCOPE_JSON="${ECU_SCOPE_JSON:-[\"VCU\"]}"
REQ_DATA="${REQ_DATA:-22F190}"
REQ_TIMEOUT_MS="${REQ_TIMEOUT_MS:-5000}"
GLOBAL_TIMEOUT_MS="${GLOBAL_TIMEOUT_MS:-300000}"
REUSE_RUNNING_APP="${REUSE_RUNNING_APP:-0}"
INJECT_REDIS_FAILURE="${INJECT_REDIS_FAILURE:-0}"
BASIC_AUTH_USER="${BASIC_AUTH_USER:-opendota}"
BASIC_AUTH_PASSWORD="${BASIC_AUTH_PASSWORD:-opendota}"

OPERATOR_ID="${OPERATOR_ID:-eng-12345}"
OPERATOR_ROLE="${OPERATOR_ROLE:-engineer}"
TENANT_ID="${TENANT_ID:-default-tenant}"
TICKET_ID="${TICKET_ID:-DIAG-2026-E2E-001}"

APP_LOG="${APP_LOG:-/tmp/opendota-single-cmd-demo-app.log}"
SSE_LOG="${SSE_LOG:-/tmp/opendota-single-cmd-demo-sse.log}"

APP_PID=""
SSE_PID=""
CHANNEL_ID=""
REDIS_STOPPED=0

log() {
  printf '[single-cmd-demo] %s\n' "$*"
}

fail() {
  printf '[single-cmd-demo] ERROR: %s\n' "$*" >&2
  if [[ -n "${APP_PID}" ]]; then
    printf '[single-cmd-demo] ---- app log tail ----\n' >&2
    tail -n 80 "${APP_LOG}" >&2 || true
  fi
  exit 1
}

cleanup() {
  if [[ "${REDIS_STOPPED}" == "1" ]]; then
    log "恢复 Redis 容器"
    docker compose -f "${COMPOSE_FILE}" up -d redis >/dev/null 2>&1 || true
    REDIS_STOPPED=0
  fi
  if [[ -n "${SSE_PID}" ]]; then
    kill "${SSE_PID}" >/dev/null 2>&1 || true
    wait "${SSE_PID}" 2>/dev/null || true
  fi
  if [[ -n "${APP_PID}" ]]; then
    log "停止本脚本启动的 Spring Boot 进程(pid=${APP_PID})"
    kill "${APP_PID}" >/dev/null 2>&1 || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
}

trap cleanup EXIT

require_cmd() {
  local cmd="$1"
  command -v "${cmd}" >/dev/null 2>&1 || fail "缺少依赖命令: ${cmd}"
}

json_value() {
  local json="$1"
  local path="$2"
  JSON_INPUT="${json}" python3 - "${path}" <<'PY'
import json
import os
import sys

path = sys.argv[1].split(".")
value = json.loads(os.environ["JSON_INPUT"])
for part in path:
    if part == "":
        continue
    if isinstance(value, list):
        value = value[int(part)]
    else:
        value = value.get(part)
if isinstance(value, (dict, list)):
    print(json.dumps(value, ensure_ascii=False))
elif value is None:
    print("")
else:
    print(value)
PY
}

psql_query() {
  local sql="$1"
  docker exec opendota-postgres psql -U opendota -d opendota -tAqc "${sql}"
}

wait_until() {
  local description="$1"
  local attempts="$2"
  local sleep_seconds="$3"
  shift 3

  local i
  for ((i = 1; i <= attempts; i++)); do
    if "$@"; then
      return 0
    fi
    log "等待 ${description}... (${i}/${attempts})"
    sleep "${sleep_seconds}"
  done
  return 1
}

wait_tcp_port() {
  local host="$1"
  local port="$2"
  python3 - "${host}" "${port}" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
try:
    with socket.create_connection((host, port), timeout=2):
        pass
except OSError:
    raise SystemExit(1)
PY
}

wait_tcp_port_down() {
  local host="$1"
  local port="$2"
  python3 - "${host}" "${port}" <<'PY'
import socket
import sys

host = sys.argv[1]
port = int(sys.argv[2])
try:
    with socket.create_connection((host, port), timeout=2):
        raise SystemExit(1)
except OSError:
    raise SystemExit(0)
PY
}

wait_postgres() {
  docker exec opendota-postgres pg_isready -U opendota -d opendota >/dev/null 2>&1
}

wait_redis() {
  [[ "$(docker exec opendota-redis redis-cli ping 2>/dev/null || true)" == "PONG" ]]
}

wait_emqx() {
  docker exec opendota-emqx emqx ctl status >/dev/null 2>&1
}

wait_app_health() {
  curl -fsS "${API_BASE_URL}/actuator/health" >/dev/null 2>&1
}

wait_log_pattern() {
  local pattern="$1"
  local file="$2"
  local attempts="$3"
  local sleep_seconds="$4"
  local i

  for ((i = 1; i <= attempts; i++)); do
    if [[ -f "${file}" ]] && grep -q "${pattern}" "${file}"; then
      return 0
    fi
    log "等待日志关键字 '${pattern}'... (${i}/${attempts})"
    sleep "${sleep_seconds}"
  done
  return 1
}

start_infra() {
  log "启动本地依赖容器(postgres / redis / emqx / kafka)"
  docker compose -f "${COMPOSE_FILE}" up -d postgres redis emqx kafka >/dev/null

  wait_until "PostgreSQL ready" 20 3 wait_postgres \
    || fail "PostgreSQL 启动超时"
  wait_until "Redis ready" 20 2 wait_redis \
    || fail "Redis 启动超时"
  wait_until "EMQX ready" 20 3 wait_emqx \
    || fail "EMQX 启动超时"
  wait_until "host PostgreSQL:5432 ready" 20 1 wait_tcp_port 127.0.0.1 5432 \
    || fail "宿主机侧 PostgreSQL 端口未就绪"
  wait_until "host Redis:6379 ready" 20 1 wait_tcp_port 127.0.0.1 6379 \
    || fail "宿主机侧 Redis 端口未就绪"
  wait_until "host EMQX:1883 ready" 20 1 wait_tcp_port 127.0.0.1 1883 \
    || fail "宿主机侧 EMQX 端口未就绪"
  sleep 2

  log "初始化 Kafka topics"
  bash "${DEPLOY_DIR}/kafka-init.sh" >/dev/null
}

start_app() {
  if wait_app_health; then
    if [[ "${REUSE_RUNNING_APP}" != "1" ]]; then
      fail "检测到 ${API_BASE_URL} 已有服务在运行。若要复用它，请设置 REUSE_RUNNING_APP=1。"
    fi
    log "复用已运行的 OpenDOTA 实例: ${API_BASE_URL}"
    return 0
  fi

  : > "${APP_LOG}"
  log "启动 opendota-app(dev profile)，日志: ${APP_LOG}"
  (
    cd "${SERVER_DIR}"
    export SPRING_SECURITY_USER_NAME="${BASIC_AUTH_USER}"
    export SPRING_SECURITY_USER_PASSWORD="${BASIC_AUTH_PASSWORD}"
    jenv exec mvn -B -ntp -f pom.xml -pl opendota-app -am -DskipTests package
    jar_path="$(find opendota-app/target -maxdepth 1 -type f -name 'opendota-app-*.jar' ! -name '*original*' | head -n 1)"
    [[ -n "${jar_path}" ]] || {
      echo "未找到可执行 app jar" >&2
      exit 1
    }
    jenv exec java -jar "${jar_path}" --spring.profiles.active=dev
  ) >"${APP_LOG}" 2>&1 &
  APP_PID="$!"

  local i
  for ((i = 1; i <= 60; i++)); do
    if wait_app_health; then
      log "Spring Boot 已就绪: ${API_BASE_URL}"
      return 0
    fi
    if ! kill -0 "${APP_PID}" >/dev/null 2>&1; then
      fail "Spring Boot 提前退出，启动失败"
    fi
    log "等待 Spring Boot 健康检查通过... (${i}/60)"
    sleep 2
  done

  fail "Spring Boot 健康检查超时"
}

start_sse_capture() {
  local sse_url="${API_BASE_URL}/api/sse/subscribe/${VIN}"

  : > "${SSE_LOG}"
  log "开始抓取 SSE 输出: ${SSE_LOG}"
  curl -sS -N --http1.1 -u "${BASIC_AUTH_USER}:${BASIC_AUTH_PASSWORD}" \
    -H "X-Operator-Id: ${OPERATOR_ID}" \
    -H "X-Operator-Role: ${OPERATOR_ROLE}" \
    -H "X-Tenant-Id: ${TENANT_ID}" \
    -H "X-Ticket-Id: ${TICKET_ID}" \
    "${sse_url}" >"${SSE_LOG}" 2>&1 &
  SSE_PID="$!"
  sleep 1

  if ! kill -0 "${SSE_PID}" >/dev/null 2>&1; then
    fail "SSE 订阅连接启动失败，请检查 ${SSE_LOG}"
  fi
}

inject_redis_failure() {
  log "注入故障: 停止 Redis，验证 sse_event 直扫兜底"
  docker compose -f "${COMPOSE_FILE}" stop redis >/dev/null
  REDIS_STOPPED=1

  wait_until "host Redis:6379 closed" 20 1 wait_tcp_port_down 127.0.0.1 6379 \
    || fail "Redis 故障注入失败，宿主机 6379 端口仍可连通"
}

restore_redis() {
  if [[ "${REDIS_STOPPED}" != "1" ]]; then
    return 0
  fi

  log "恢复 Redis，验证健康探针回切"
  docker compose -f "${COMPOSE_FILE}" up -d redis >/dev/null
  wait_until "Redis ready" 20 2 wait_redis \
    || fail "Redis 恢复超时"
  wait_until "host Redis:6379 ready" 20 1 wait_tcp_port 127.0.0.1 6379 \
    || fail "Redis 恢复后宿主机 6379 端口未就绪"
  REDIS_STOPPED=0
}

post_json() {
  local url="$1"
  local body="$2"
  curl -sS -X POST "${url}" \
    -u "${BASIC_AUTH_USER}:${BASIC_AUTH_PASSWORD}" \
    -H 'Content-Type: application/json' \
    -H "X-Operator-Id: ${OPERATOR_ID}" \
    -H "X-Operator-Role: ${OPERATOR_ROLE}" \
    -H "X-Tenant-Id: ${TENANT_ID}" \
    -H "X-Ticket-Id: ${TICKET_ID}" \
    --data "${body}"
}

assert_api_success() {
  local response="$1"
  local code
  code="$(json_value "${response}" "code")"
  [[ "${code}" == "0" ]] || fail "接口返回失败: ${response}"
}

open_channel() {
  local body response
  body="$(cat <<EOF
{
  "vin": "${VIN}",
  "ecuName": "${ECU_NAME}",
  "ecuScope": ${ECU_SCOPE_JSON},
  "transport": "UDS_ON_CAN",
  "txId": "0x7E0",
  "rxId": "0x7E8",
  "globalTimeoutMs": ${GLOBAL_TIMEOUT_MS}
}
EOF
)"

  response="$(post_json "${API_BASE_URL}/api/channel/open" "${body}")"
  assert_api_success "${response}"

  CHANNEL_ID="$(json_value "${response}" "data.channelId")"
  local msg_id
  msg_id="$(json_value "${response}" "data.msgId")"
  [[ -n "${CHANNEL_ID}" ]] || fail "开通道未返回 channelId: ${response}"
  [[ -n "${msg_id}" ]] || fail "开通道未返回 msgId: ${response}"

  log "channel_open 成功: channelId=${CHANNEL_ID}, msgId=${msg_id}"
}

send_single_cmd() {
  local body response single_msg_id
  body="$(cat <<EOF
{
  "channelId": "${CHANNEL_ID}",
  "type": "raw_uds",
  "reqData": "${REQ_DATA}",
  "timeoutMs": ${REQ_TIMEOUT_MS}
}
EOF
)"

  response="$(post_json "${API_BASE_URL}/api/cmd/single" "${body}")"
  assert_api_success "${response}"

  single_msg_id="$(json_value "${response}" "data.msgId")"
  [[ -n "${single_msg_id}" ]] || fail "single_cmd 未返回 msgId: ${response}"
  log "single_cmd 已下发: msgId=${single_msg_id}, reqData=${REQ_DATA}" >&2
  printf '%s\n' "${single_msg_id}"
}

wait_diag_record() {
  local msg_id="$1"
  local row=""
  local i

  for ((i = 1; i <= 20; i++)); do
    row="$(psql_query "
      SELECT msg_id || '|' || status || '|' || COALESCE(res_raw_hex, '') || '|' || COALESCE(translated->>'summaryText', '')
      FROM diag_record
      WHERE msg_id = '${msg_id}'
      ORDER BY id DESC
      LIMIT 1;
    ")"
    if [[ -n "${row}" ]]; then
      local row_msg_id status res_raw summary
      IFS='|' read -r row_msg_id status res_raw summary <<<"${row}"
      if [[ "${status}" != "-1" && -n "${res_raw}" && -n "${summary}" ]]; then
        log "diag_record 校验通过: status=${status}, resRawHex=${res_raw}, summary=${summary}"
        return 0
      fi
    fi
    log "等待 diag_record 写入... (${i}/20)"
    sleep 1
  done

  if [[ -z "${row}" ]]; then
    fail "20 秒内未查到 diag_record(msgId=${msg_id})"
  fi
  fail "diag_record 在 20 秒内未从 pending 转为完成态: ${row}"
}

wait_sse_event() {
  local msg_id="$1"
  local row=""
  local i

  for ((i = 1; i <= 20; i++)); do
    row="$(psql_query "
      SELECT id || '|' || event_type || '|' || COALESCE(payload_summary->>'msgId', '') || '|' || COALESCE(payload_summary->>'summaryText', '')
      FROM sse_event
      WHERE event_type = 'diag-result'
        AND payload_summary->>'msgId' = '${msg_id}'
      ORDER BY id DESC
      LIMIT 1;
    ")"
    if [[ -n "${row}" ]]; then
      break
    fi
    log "等待 sse_event 写入... (${i}/20)"
    sleep 1
  done

  [[ -n "${row}" ]] || fail "20 秒内未查到 sse_event(msgId=${msg_id})"
  local event_id event_type payload_msg_id summary
  IFS='|' read -r event_id event_type payload_msg_id summary <<<"${row}"
  [[ "${event_type}" == "diag-result" ]] || fail "sse_event.event_type 不正确: ${row}"
  [[ "${payload_msg_id}" == "${msg_id}" ]] || fail "sse_event payload msgId 不匹配: ${row}"
  log "sse_event 校验通过: id=${event_id}, summary=${summary}"
}

wait_realtime_sse() {
  local msg_id="$1"
  local i

  [[ -n "${SSE_PID}" ]] || fail "SSE 捕获进程未启动"

  for ((i = 1; i <= 20; i++)); do
    if grep -q 'diag-result' "${SSE_LOG}" && grep -q "${msg_id}" "${SSE_LOG}"; then
      log "SSE 实时校验通过: msgId=${msg_id}"
      return 0
    fi
    if ! kill -0 "${SSE_PID}" >/dev/null 2>&1; then
      fail "SSE 连接提前退出，未捕获到实时事件。详见 ${SSE_LOG}"
    fi
    log "等待 SSE 实时事件... (${i}/20)"
    sleep 1
  done

  fail "20 秒内未在 SSE 流中捕获到 diag-result(msgId=${msg_id})"
}

close_channel() {
  if [[ -z "${CHANNEL_ID}" ]]; then
    return 0
  fi

  local response
  response="$(post_json "${API_BASE_URL}/api/channel/${CHANNEL_ID}/close" '{"resetSession": true}')"
  assert_api_success "${response}"
  log "channel_close 成功: channelId=${CHANNEL_ID}"
  CHANNEL_ID=""
}

check_sse_capture() {
  kill "${SSE_PID}" >/dev/null 2>&1 || true
  wait "${SSE_PID}" 2>/dev/null || true
  SSE_PID=""

  grep -q 'diag-result' "${SSE_LOG}" || fail "SSE 日志中未找到 diag-result: ${SSE_LOG}"
  log "SSE 实时输出已捕获，详见 ${SSE_LOG}"
}

main() {
  require_cmd bash
  require_cmd curl
  require_cmd docker
  require_cmd jenv
  require_cmd java
  require_cmd mvn
  require_cmd python3

  log "开始本地单步诊断联调: vin=${VIN}, ecu=${ECU_NAME}, reqData=${REQ_DATA}"
  start_infra
  start_app
  start_sse_capture
  open_channel
  if [[ "${INJECT_REDIS_FAILURE}" == "1" ]]; then
    inject_redis_failure
  fi

  local single_msg_id
  single_msg_id="$(send_single_cmd)"
  wait_realtime_sse "${single_msg_id}"
  wait_diag_record "${single_msg_id}"
  wait_sse_event "${single_msg_id}"
  if [[ "${INJECT_REDIS_FAILURE}" == "1" ]]; then
    wait_log_pattern "Redis 故障,切换到 sse_event 直扫模式" "${APP_LOG}" 20 1 \
      || fail "未在应用日志中观察到 Redis 故障降级"
    restore_redis
    wait_log_pattern "Redis 已恢复,停用 sse_event 直扫模式" "${APP_LOG}" 20 1 \
      || fail "未在应用日志中观察到 Redis 恢复回切"
  fi
  close_channel
  check_sse_capture

  log "联调完成。关键产物:"
  log "  app log : ${APP_LOG}"
  if [[ -s "${SSE_LOG}" ]]; then
    log "  sse log : ${SSE_LOG}"
  fi
}

main "$@"
