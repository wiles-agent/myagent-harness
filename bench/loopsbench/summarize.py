#!/usr/bin/env python3
"""汇总 LoopsBench 上 myagent 的运行：每个标签、每道题的通过情况，加上 myagent 自己的摘要
（token、缓存命中、步数、派了几个子 agent、分别是什么类型）。

  python3 bench/loopsbench/summarize.py ~/workspace/Loopsbench/runs/<label> [<label 目录>...]
"""
import collections
import glob
import json
import os
import sys


def summaries(trial_dir):
    files = sorted(glob.glob(os.path.join(trial_dir, "agent-logs", "outer-loop", "round-*", "myagent-summary.json")))
    if not files:
        files = glob.glob(os.path.join(trial_dir, "agent-logs", "myagent-summary.json"))
    out = []
    for f in files:
        try:
            out.append(json.load(open(f, encoding="utf-8")))
        except ValueError:
            pass
    return out


def tests(trial_dir):
    """从 results.json 之外的 trial 级结果里拿通过/失败的测试数（字段名随框架版本可能变，拿不到就算了）。"""
    for name in ("result.json", "trial_result.json", "results.json"):
        f = os.path.join(trial_dir, name)
        if os.path.exists(f):
            try:
                return json.load(open(f, encoding="utf-8"))
            except ValueError:
                return None
    return None


def walk(children):
    """subagentReport 的 children 连同嵌套的孙一级（挂在各自的 children 下）一起展开。"""
    for c in children or []:
        yield c
        yield from walk(c.get("children"))


def main(dirs):
    for label_dir in dirs:
        label = os.path.basename(os.path.normpath(label_dir))
        for run in sorted(glob.glob(os.path.join(label_dir, "*", "results.json"))):
            res = json.load(open(run, encoding="utf-8"))
            run_dir = os.path.dirname(run)
            trials = res.get("results") or res.get("trial_results") or []
            for t in trials:
                task = t.get("task_id")
                trial_dir = next(iter(glob.glob(os.path.join(run_dir, task, t.get("trial_name", "*")))), None)
                rounds = summaries(trial_dir) if trial_dir else []
                prompt = sum(r.get("promptTokens", 0) for r in rounds)
                cached = sum(r.get("cachedPromptTokens", 0) for r in rounds)
                done = sum(r.get("completionTokens", 0) for r in rounds)
                steps = sum(r.get("steps", 0) for r in rounds)
                kinds = collections.Counter(c.get("type") for r in rounds
                                            for c in walk((r.get("subagentReport") or {}).get("children")))
                secs = sum(r.get("durationMs", 0) for r in rounds) / 1000
                ft = {k: v for k, v in (t.get("final_test_results") or {}).items() if not k.startswith("__")}
                passed = sum(1 for v in ft.values() if v == "passed")
                print(f"{label:16} {task:32} resolved={t.get('is_resolved')} "
                      f"tests={passed}/{len(ft)} "
                      f"rounds={len(rounds)} steps={steps} prompt={prompt} cached={cached} "
                      f"({(cached / prompt * 100 if prompt else 0):.0f}%) out={done} "
                      f"subagents={dict(kinds) or 0} agent_time={secs:.0f}s "
                      f"failure={t.get('failure_mode')}")


if __name__ == "__main__":
    main(sys.argv[1:])
