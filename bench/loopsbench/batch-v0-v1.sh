#!/usr/bin/env bash
# 提示词对照第一批：V0（原提示词）补跑上一轮判分无效的 4 道，V1（开工先判断要不要拆）跑全部 7 道。
# 两组都用改动后的 jar、判分阶段开网（run-verifier-net.yaml）。
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
export LOOPSBENCH_TASKS=$HOME/workspace/loopsbench-data/tasks
JAR=$HOME/workspace/myagent-jars/after-fed18f3.jar
CFG=(--config "$HERE/run-verifier-net.yaml" --n-concurrent 2)
ALL=task_os_c_fs_labs,task_systems_csapp_labs,task_ml_four_assignments,task_mlp_numpy_coursework,task_ds_five_assignments,task_os_distributed_practicals,task_sql_engine_myjql
REDO=task_os_c_fs_labs,task_ml_four_assignments,task_mlp_numpy_coursework,task_ds_five_assignments

"$HERE/run.sh" v0-after-on "$JAR" on "$REDO" "${CFG[@]}" > "$HOME/workspace/v0-after-on.log" 2>&1
"$HERE/run.sh" v1-after-on "$JAR" on "$ALL" -k prompts_dir="$HERE/variants/v1-plan" "${CFG[@]}" > "$HOME/workspace/v1-after-on.log" 2>&1
echo BATCH_DONE
