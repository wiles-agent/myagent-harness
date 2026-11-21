#!/usr/bin/env bash
# 在 WSL / Linux 里用 LoopsBench 跑 myagent 一组配置。
#
#   bench/loopsbench/run.sh <label> <jar> <subagents:on|off> <task_id,...> [额外的 loopsbench 参数...]
#   bench/loopsbench/run.sh after-on ~/workspace/myagent-jars/after-fed18f3.jar on task_tcp_course_stack
#
# 前提：~/workspace/Loopsbench 里 `uv sync` 过（WSL 里 core.autocrlf=false 检出，否则脚本是 CRLF、容器起不来）；
# 模型配置读 MYAGENT_CONFIG（默认 Windows 那份 ~/.myagent/config.json），key 只进环境变量，不上命令行。
set -euo pipefail
LABEL=$1; JAR=$2; SUB=$3; TASKS=$4; shift 4
LB=${LOOPSBENCH_ROOT:-$HOME/workspace/Loopsbench}
HERE="$(cd "$(dirname "$0")" && pwd)"
CONFIG=${MYAGENT_CONFIG:-/mnt/c/Users/$(cmd.exe /c 'echo %USERNAME%' 2>/dev/null | tr -d '\r')/.myagent/config.json}
read_config() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$CONFIG" "$1"; }

export DEEPSEEK_API_KEY="$(read_config apiKey)"
BASE_URL="$(read_config baseUrl)"; BASE_URL=${BASE_URL:-https://api.deepseek.com}
MODEL="$(read_config model)"; MODEL=${MODEL:-deepseek-v4.1-flash}

TASK_ARGS=()
IFS=, read -r -a IDS <<< "$TASKS"
for t in "${IDS[@]}"; do TASK_ARGS+=(--task-id "$t"); done

cd "$LB"
PYTHONPATH="$HERE" "${UV:-$HOME/.local/bin/uv}" run loopsbench run \
  --dataset-path "${LOOPSBENCH_TASKS:-tasks}" "${TASK_ARGS[@]}" \
  --agent-import-path myagent_agent:MyAgent --model "llm/$MODEL" \
  -k jar="$JAR" -k base_url="$BASE_URL" -k subagents="$SUB" \
  --output-path "runs/$LABEL" "$@"
