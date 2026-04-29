#!/usr/bin/env bash
# Redis 故障场景入口:
#   1. 启动健康链路
#   2. 在 single_cmd 前主动停止 Redis
#   3. 验证 SSE 仍通过 sse_event 直扫收到 diag-result
#   4. 恢复 Redis 并等待健康探针回切

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export INJECT_REDIS_FAILURE=1
exec bash "${ROOT_DIR}/scripts/e2e/single-cmd-demo.sh" "$@"
