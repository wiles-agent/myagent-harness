# LoopsBench 接入

把 myagent 接进 [LoopsBench](https://github.com/microsoft/Loopsbench)（112 道长任务，每道按依赖关系拆成能单独测试的开发单元）。
用来回答「子 agent 在长任务上有没有用」——它是公开研究里子 agent 并发唯一测出过收益的基准。

## 文件

- `myagent_agent.py`：适配器（`--agent-import-path myagent_agent:MyAgent`）。JRE 从本机 `eclipse-temurin:21-jre` 镜像取，
  key 经 Docker API 写进容器、不进 tmux 记录；`--once` 摘要、stderr、对话记录（`MYAGENT_TRANSCRIPT`）都拷出来。
  框架时限前 90 秒发 SIGTERM，myagent 的退出钩子照样写摘要。`prompts_dir=` 能在 jar 外面覆盖提示词做对照。
- `run.sh <标签> <jar> <on|off> <任务,...> [loopsbench 参数]`：跑一组。模型配置读 Windows 那份 `~/.myagent/config.json`。
- `run-verifier-net.yaml`：判分阶段开网（见下面的坑），对照组都用它。
- `summarize.py <runs/标签>...`：每道题一行——解出、测试通过数、步数、token、缓存命中、子 agent 类型。
- `variants/`：提示词对照用的覆盖文件。`v1-plan` 在主提示词里要求「动手前先判断要不要拆」。
- `batch-v0-v1.sh`：第一批提示词对照。

## 跑起来（Windows + WSL）

```bash
# WSL 里：LoopsBench 用 uv 装（系统 Python 缺 venv），检出要用 LF
git -C ~/workspace/Loopsbench config core.autocrlf false
cd ~/workspace/Loopsbench && uv sync --python 3.12
# 题库：Hugging Face 上 1.5GB 的 loopsbench-tasks-*.tar.zst，解到 ~/workspace/loopsbench-data/tasks
LOOPSBENCH_TASKS=~/workspace/loopsbench-data/tasks bench/loopsbench/run.sh probe ~/myagent.jar on task_sql_engine_myjql \
  --config bench/loopsbench/run-verifier-net.yaml
```

## 踩过的坑

- **Docker Desktop 要对 WSL 发行版开集成**，否则 WSL 里没有 docker。
- **`docker build` 解析 Docker Hub 基础镜像时不走 Desktop 的代理**（`docker pull` 走）：先 `docker pull` 好
  egress 的 golang / gogost 和题目用到的基础镜像，构建就用本地的。
- **Windows 上 clone 的仓库脚本是 CRLF**，容器里 `#!/bin/sh\r` 找不到解释器、退出码 127。
- **好几道题的 `run-tests.sh` 在判分时才 apt-get / 装 uv / pip install**，而默认判分容器断网，测试结果为空。
  `run-verifier-net.yaml` 只给 verifier 开网，agent 干活时仍只通模型端点。
- Git Bash 调 `wsl` 时会改写像路径的参数，要 `MSYS_NO_PATHCONV=1`。

## 读数（2026-11，deepseek-v4.1-flash，每题 1 次）

7 道「同层至少 4 个模块能并行」的 easy/medium 题，默认提示词：解出 2/7，测试通过 298/329（91%），
1000 多步里 `spawn_agent` 0 次，缓存命中 95%～99%。失败大多差在收尾（41/42、145/153）和超时，不在并行度。
主提示词明确要求先判断要不要拆（`v1-plan`）后，20 步诊断里出现了一次合理的拆分；完整对照因额度用完没跑。
