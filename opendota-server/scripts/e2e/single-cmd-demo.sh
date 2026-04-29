#!/usr/bin/env bash
# ============================================================================
# single-cmd-demo.sh — OpenDOTA 单步诊断端到端验证脚本
# ============================================================================
#
# 前置条件:
#   1. Spring Boot 应用已启动 (localhost:8080)
#   2. EMQX MQTT broker 已启动 (localhost:1883)
#   3. MqttVehicleSimulator 已启用 (opendota.simulator.enabled=true)
#   4. PostgreSQL + Redis 已启动
#
# 用法:
#   ./scripts/e2e/single-cmd-demo.sh [--vin VIN] [--ecu ECU] [--uds UDS_PDU]
#
# 示例:
#   ./scripts/e2e/single-cmd-demo.sh
#   ./scripts/e2e/single-cmd-demo.sh --vin LSVWA234567890123 --ecu VCU --uds 22F190
# ============================================================================

set -euo pipefail

# ---------- 默认参数 ----------
BASE_URL="${OPENDOTA_BASE_URL:-http://localhost:8080}"
VIN="${OPENDOTA_VIN:-LSVWA234567890123}"
ECU="${OPENDOTA_ECU:-VCU}"
UDS_PDU="${OPENDOTA_UDS_PDU:-22F190}"
TIMEOUT_MS="${OPENDOTA_TIMEOUT_MS:-10000}"
OPERATOR_ID="${OPENDOTA_OPERATOR_ID:-operator-e2e}"
TENANT_ID="${OPENDOTA_TENANT_ID:-default-tenant}"

# ---------- 颜色 ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; exit 1; }

# ---------- 参数解析 ----------
while [[ $# -gt 0 ]]; do
    case $1 in
        --vin)      VIN="$2";       shift 2 ;;
        --ecu)      ECU="$2";       shift 2 ;;
        --uds)      UDS_PDU="$2";   shift 2 ;;
        --base-url) BASE_URL="$2";  shift 2 ;;
        *)          echo "未知参数: $1"; exit 1 ;;
    esac
done

# ---------- 前置检查 ----------
info "前置检查..."

command -v curl >/dev/null 2>&1 || fail "需要 curl"
command -v jq   >/dev/null 2>&1 || fail "需要 jq"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/api/hello" 2>/dev/null || echo "000")
if [[ "$HTTP_CODE" != "200" ]]; then
    fail "服务未启动 (${BASE_URL}/api/hello → HTTP ${HTTP_CODE})\n  请先执行: mvn spring-boot:run -pl opendota-app -am"
fi
ok "服务已启动 (${BASE_URL})"

CURL_OPTS=(-s -S --connect-timeout 5 --max-time 30)
HEADERS=(-H "Content-Type: application/json" -H "X-Operator-Id: ${OPERATOR_ID}" -H "X-Operator-Role: engineer" -H "X-Tenant-Id: ${TENANT_ID}" -H "X-Ticket-Id: TICKET-E2E")

# ---------- Step 1: 开通道 ----------
info "Step 1: 开通道 (VIN=${VIN}, ECU=${ECU})"

CHANNEL_RESP=$(curl "${CURL_OPTS[@]}" "${HEADERS[@]}" \
    -X POST "${BASE_URL}/api/channel/open" \
    -d "{
        \"vin\": \"${VIN}\",
        \"ecuName\": \"${ECU}\",
        \"ecuScope\": [\"${ECU}\"],
        \"transport\": \"UDS_ON_CAN\",
        \"txId\": \"0x7E0\",
        \"rxId\": \"0x7E8\"
    }")

CHANNEL_ID=$(echo "$CHANNEL_RESP" | jq -r '.channelId // empty')
MSG_ID_OPEN=$(echo "$CHANNEL_RESP" | jq -r '.msgId // empty')

if [[ -z "$CHANNEL_ID" ]]; then
    fail "开通道失败:\n${CHANNEL_RESP}"
fi
ok "通道已开: channelId=${CHANNEL_ID}, msgId=${MSG_ID_OPEN}"

# 等待模拟器处理 channel_open 并回 channel_event
sleep 1

# ---------- Step 2: 下发 single_cmd ----------
info "Step 2: 下发 single_cmd (UDS: ${UDS_PDU})"

CMD_RESP=$(curl "${CURL_OPTS[@]}" "${HEADERS[@]}" \
    -X POST "${BASE_URL}/api/cmd/single" \
    -d "{
        \"channelId\": \"${CHANNEL_ID}\",
        \"type\": \"raw_uds\",
        \"reqData\": \"${UDS_PDU}\",
        \"timeoutMs\": ${TIMEOUT_MS}
    }")

MSG_ID_CMD=$(echo "$CMD_RESP" | jq -r '.msgId // empty')

if [[ -z "$MSG_ID_CMD" ]]; then
    fail "下发 single_cmd 失败:\n${CMD_RESP}"
fi
ok "命令已下发: msgId=${MSG_ID_CMD}"

# ---------- Step 3: SSE 等待响应 ----------
info "Step 3: 等待 SSE 推送响应 (最多 ${TIMEOUT_MS}ms + 5s 余量)...

SSE_WAIT_SEC=$(( (TIMEOUT_MS / 1000) + 5 ))
SSE_URL="${BASE_URL}/api/sse/subscribe/${VIN}"
SSE_OUTPUT=$(mktemp)

# 后台 curl SSE，超时后自动退出
curl "${CURL_OPTS[@]}" --max-time "${SSE_WAIT_SEC}" \
    -H "Accept: text/event-stream" \
    "${SSE_URL}" > "${SSE_OUTPUT}" 2>/dev/null &
SSE_PID=$!

# 等待 single_resp 出现或超时
WAIT_COUNT=0
MAX_WAIT=$((SSE_WAIT_SEC * 2))
RESP_FOUND=false

while [[ $WAIT_COUNT -lt $MAX_WAIT ]]; do
    if grep -q "diag-result" "${SSE_OUTPUT}" 2>/dev/null; then
        RESP_FOUND=true
        break
    fi
    # 检查 SSE 进程是否还在
    if ! kill -0 "$SSE_PID" 2>/dev/null; then
        break
    fi
    sleep 0.5
    WAIT_COUNT=$((WAIT_COUNT + 1))
done

# 清理 SSE 进程
kill "$SSE_PID" 2>/dev/null || true
wait "$SSE_PID" 2>/dev/null || true

if $RESP_FOUND; then
    ok "收到 SSE 推送响应"
    echo ""
    echo -e "${CYAN}=== SSE 事件 ===${NC}"
    # 提取 diag-result 相关的 data 行
    grep -A2 "diag-result" "${SSE_OUTPUT}" | head -10
    echo ""
else
    warn "未在 ${SSE_WAIT_SEC}s 内收到 SSE 推送(diag-result)"
    warn "可能原因: Redis 未启动 / 模拟器未响应 / SSE 未连接"
    echo ""
    echo -e "${YELLOW}=== SSE 原始输出 ===${NC}"
    cat "${SSE_OUTPUT}" | head -20
fi

rm -f "${SSE_OUTPUT}"

# ---------- Step 4: 关通道 ----------
info "Step 4: 关通道"

CLOSE_RESP=$(curl "${CURL_OPTS[@]}" "${HEADERS[@]}" \
    -X POST "${BASE_URL}/api/channel/${CHANNEL_ID}/close" \
    -d "{}")

MSG_ID_CLOSE=$(echo "$CLOSE_RESP" | jq -r '.msgId // empty')

if [[ -n "$MSG_ID_CLOSE" ]]; then
    ok "通道已关闭: msgId=${MSG_ID_CLOSE}"
else
    warn "关通道可能失败:\n${CLOSE_RESP}"
fi

# ---------- Step 5: Prometheus 指标检查 ----------
info "Step 5: 检查 Prometheus 指标"

METRICS_RESP=$(curl "${CURL_OPTS[@]}" "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "")

if [[ -n "$METRICS_RESP" ]]; then
    CMD_TOTAL=$(echo "$METRICS_RESP" | grep "dota_single_cmd_total" | head -1 | awk '{print $2}')
    CMD_LATENCY=$(echo "$METRICS_RESP" | grep "dota_single_cmd_latency_seconds_count" | head -1 | awk '{print $2}')

    if [[ -n "$CMD_TOTAL" ]]; then
        ok "dota_single_cmd_total = ${CMD_TOTAL}"
    else
        warn "dota_single_cmd_total 指标未找到"
    fi

    if [[ -n "$CMD_LATENCY" ]]; then
        ok "dota_single_cmd_latency_seconds_count = ${CMD_LATENCY}"

        # 检查 P95 percentile 是否暴露
        P95=$(echo "$METRICS_RESP" | grep 'dota_single_cmd_latency_seconds.*quantile="0.95"' | head -1 | awk '{print $2}')
        if [[ -n "$P95" ]]; then
            ok "P95 latency = ${P95}s"
        else
            warn "P95 percentile 未暴露(可能需要 PrometheusMeterRegistry)"
        fi
    else
        warn "dota_single_cmd_latency_seconds 指标未找到"
    fi
else
    warn "无法访问 /actuator/prometheus"
fi

# ---------- 汇总 ----------
echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  E2E 验证完成${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""
echo "  VIN:       ${VIN}"
echo "  ECU:       ${ECU}"
echo "  UDS PDU:   ${UDS_PDU}"
echo "  Channel:   ${CHANNEL_ID}"
echo "  Cmd msgId: ${MSG_ID_CMD}"
echo ""
