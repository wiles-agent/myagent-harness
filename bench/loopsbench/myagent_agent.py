"""LoopsBench 的 myagent 适配器：在任务容器里用 `java -jar myagent.jar --once` 跑一次。

接法照 LoopsBench 自带的 Codex 适配器（loopsbench/agents/codex/codex_agent.py）：
  install       一次：把 JRE 和 myagent.jar 拷进容器的 /opt/myagent
  prepare       每次会话：写 ~/.myagent/config.json（key 走 Docker API，不进 tmux 记录），导出子 agent 的开关
  perform_task  把题面写成文件，--once 跑完；--once-out 的摘要（token、缓存命中、子 agent 报告）一起拷出来

JRE：不传 `jre` 时从本机的 eclipse-temurin 镜像里取 /opt/java/openjdk（任务镜像是 glibc 的 ubuntu，直接能跑）。

用法（在 Loopsbench 仓库里，PYTHONPATH 指到本目录）：
  PYTHONPATH=/path/to/myagent/bench/loopsbench loopsbench run --dataset-path tasks \\
    --task-id task_tcp_course_stack --agent-import-path myagent_agent:MyAgent \\
    --model deepseek/deepseek-v4.1-flash --agent-kwarg jar=/path/to/myagent.jar --agent-kwarg subagents=on

agent kwargs：
  jar          myagent.jar 的路径（必填；对照新旧版本就传不同的 jar）
  jre          linux-x64 JRE 的 .tar.gz；不传就从 jre_image 里取
  jre_image    默认 eclipse-temurin:21-jre
  base_url     默认 DEEPSEEK_BASE_URL 或 https://api.deepseek.com
  api_key_env  默认 DEEPSEEK_API_KEY
  subagents    on / off -> MYAGENT_SUBAGENTS
  subagent_roles / subagent_max / subagent_steps / subagent_timeout -> 对应的 MYAGENT_SUBAGENT_*
  max_steps    主 agent 步数上限，默认 0（不限）
  thinking     传了就写进 config.json 的 thinking
  prompts_dir  本机一个目录，里面是 prompts/... 的覆盖文件（如 prompts/modes/agent.md）；
               放在 classpath 上 jar 的前面，同名的提示词就用它的——不用重新打包就能对照着试措辞
"""

from __future__ import annotations

import io
import json
import os
import re
import shlex
import tarfile
from pathlib import Path

from loopsbench.agents.base_agent import AgentResult, BaseAgent
from loopsbench.terminal.network_policy import model_endpoint_hosts
from loopsbench.terminal.tmux_session import TmuxSession

_RT = "/opt/myagent"
_JAVA = f"{_RT}/openjdk/bin/java"
_JAR = f"{_RT}/myagent.jar"
_HOME = "/root"
_PROMPT = "/tmp/myagent-prompt.txt"
_OUT = "/tmp/myagent-out.json"
_STDERR = "/tmp/myagent-stderr.log"
_TRANSCRIPT = "/tmp/myagent-transcript.json"

_ENV_KWARGS = {
    "subagents": "MYAGENT_SUBAGENTS",
    "subagent_roles": "MYAGENT_SUBAGENT_ROLES",
    "subagent_max": "MYAGENT_SUBAGENT_MAX",
    "subagent_steps": "MYAGENT_SUBAGENT_STEPS",
    "subagent_timeout": "MYAGENT_SUBAGENT_TIMEOUT",
}


class MyAgent(BaseAgent):

    def __init__(self, model_name: str | None = None, **kwargs):
        super().__init__(**kwargs)
        model = (model_name or kwargs.get("model") or "deepseek/deepseek-v4.1-flash").strip()
        self._model = model.split("/", 1)[1] if "/" in model else model

        jar = kwargs.get("jar") or os.environ.get("MYAGENT_JAR")
        if not jar or not Path(jar).expanduser().is_file():
            raise ValueError("myagent 适配器要 jar=<myagent.jar 路径>（或 MYAGENT_JAR）")
        self._jar = Path(jar).expanduser().resolve()
        self._jre = kwargs.get("jre")
        self._jre_image = kwargs.get("jre_image", "eclipse-temurin:21-jre")

        self._base_url = (kwargs.get("base_url") or os.environ.get("DEEPSEEK_BASE_URL")
                          or "https://api.deepseek.com").rstrip("/")
        key_env = kwargs.get("api_key_env", "DEEPSEEK_API_KEY")
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key_env):
            raise ValueError("api_key_env 要是环境变量名")
        self._api_key = kwargs.get("api_key") or os.environ.get(key_env)
        if not self._api_key or any(c in self._api_key for c in "\r\n\0"):
            raise ValueError(f"myagent 适配器要 API key：设 {key_env} 或传 api_key")

        self._env = {}
        for key, env in _ENV_KWARGS.items():
            if kwargs.get(key) is not None:
                value = str(kwargs[key])
                if not re.fullmatch(r"[A-Za-z0-9_,.-]*", value):
                    raise ValueError(f"{key} 的值只能是字母数字和 , . - _: {value!r}")
                self._env[env] = value
        self._max_steps = int(kwargs.get("max_steps", 0))
        self._thinking = kwargs.get("thinking")
        self._prompts_dir = Path(kwargs["prompts_dir"]).expanduser().resolve() if kwargs.get("prompts_dir") else None
        if self._prompts_dir is not None and not (self._prompts_dir / "prompts").is_dir():
            raise ValueError("prompts_dir 下面要有一个 prompts/ 目录，结构和 jar 里的一样")

    @staticmethod
    def name() -> str:
        return "myagent"

    def model_network_hosts(self) -> list[str]:
        return list(dict.fromkeys([*super().model_network_hosts(), *model_endpoint_hosts([self._base_url])]))

    def get_trajectory_paths(self) -> list[str]:
        return [_OUT, _STDERR, _PROMPT, _TRANSCRIPT]

    # ------------------------------------------------------------------ 容器文件

    @staticmethod
    def _put(container, directory: str, name: str, data: bytes, mode: int = 0o600) -> None:
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            entry = tarfile.TarInfo(name)
            entry.mode, entry.size = mode, len(data)
            archive.addfile(entry, io.BytesIO(data))
        if not container.put_archive(directory, stream.getvalue()):
            raise RuntimeError(f"写不进容器: {directory}/{name}")

    def _jre_archive(self, client) -> bytes:
        """一个顶层目录叫 openjdk 的 tar。"""
        if self._jre:
            # 外面给的 .tar.gz：重新打成顶层 openjdk/ 的 tar
            src = tarfile.open(Path(self._jre).expanduser(), "r:gz")
            out = io.BytesIO()
            with tarfile.open(fileobj=out, mode="w") as dst:
                for member in src.getmembers():
                    parts = member.name.split("/", 1)
                    member.name = "openjdk" + ("/" + parts[1] if len(parts) > 1 else "")
                    dst.addfile(member, src.extractfile(member) if member.isfile() else None)
            return out.getvalue()
        try:
            client.images.get(self._jre_image)
        except Exception:
            client.images.pull(self._jre_image)
        holder = client.containers.create(self._jre_image, command="true")
        try:
            chunks, _ = holder.get_archive("/opt/java/openjdk")
            return b"".join(chunks)
        finally:
            holder.remove(force=True)

    # ------------------------------------------------------------------ 钩子

    def install(self, session: TmuxSession, logging_dir: Path | None = None) -> None:
        container = session._container
        if container.exec_run(["mkdir", "-p", _RT, f"{_HOME}/.myagent"]).exit_code:
            raise RuntimeError("建不了 /opt/myagent")
        if not container.put_archive(_RT, self._jre_archive(container.client)):
            raise RuntimeError("JRE 拷不进容器")
        self._put(container, _RT, "myagent.jar", self._jar.read_bytes(), 0o644)
        if self._prompts_dir is not None:
            stream = io.BytesIO()
            with tarfile.open(fileobj=stream, mode="w") as archive:
                archive.add(self._prompts_dir / "prompts", arcname="override/prompts")
            if not container.put_archive(_RT, stream.getvalue()):
                raise RuntimeError("提示词覆盖目录拷不进容器")
        check = container.exec_run([_JAVA, "-version"])
        if check.exit_code:
            raise RuntimeError("容器里的 java 跑不起来: " + check.output.decode(errors="replace")[-300:])

    def prepare(self, session: TmuxSession, logging_dir: Path | None = None) -> None:
        config = {"apiKey": self._api_key, "baseUrl": self._base_url, "model": self._model}
        if self._thinking is not None:
            config["thinking"] = self._thinking
        container = session._container
        container.exec_run(["mkdir", "-p", f"{_HOME}/.myagent"])
        # key 走 Docker API 写文件，不出现在 tmux 的按键记录里；config.json 也不在拷出的轨迹里
        self._put(container, f"{_HOME}/.myagent", "config.json", json.dumps(config).encode())
        for env, value in self._env.items():
            session.send_keys(f"export {env}={shlex.quote(value)} Enter", block=True, max_timeout_sec=10.0)

    def perform_task(self, instruction: str, session: TmuxSession, logging_dir: Path | None = None,
                     timeout_sec: float | None = None) -> AgentResult:
        container = session._container
        self._put(container, "/tmp", Path(_PROMPT).name, instruction.encode("utf-8"), 0o644)
        container.exec_run(["rm", "-f", _OUT, _TRANSCRIPT])
        steps = f"--max-steps {self._max_steps} " if self._max_steps > 0 else ""
        # 框架到点会直接放弃等待；在那之前 90 秒先发 SIGTERM，myagent 的退出钩子把截至此刻的摘要写出来
        guard = (f"timeout -s TERM -k 30 {max(60, int(timeout_sec) - 90)} "
                 if timeout_sec is not None else "")
        # 有覆盖目录时用 -cp 把它排在 jar 前面：PromptRepository 按 classpath 顺序找 /prompts/...
        launch = (f"-cp {_RT}/override:{_JAR} dev.wiles.myagent.Main" if self._prompts_dir is not None
                  else f"-jar {_JAR}")
        cmd = (f"cd /workspace && MYAGENT_TRANSCRIPT={_TRANSCRIPT} {guard}{_JAVA} -Duser.home={_HOME} -Xmx1g {launch} "
               f"--once {_PROMPT} -d /workspace {steps}--once-out {_OUT} 2>{_STDERR}")
        session.send_keys(f"{cmd} Enter", block=True,
                          max_timeout_sec=timeout_sec if timeout_sec is not None else 86400.0)

        result = container.exec_run(["cat", _OUT])
        summary = {}
        if result.exit_code == 0:
            try:
                summary = json.loads(result.output.decode("utf-8"))
            except ValueError:
                pass
        if logging_dir is not None:
            Path(logging_dir).mkdir(parents=True, exist_ok=True)
            (Path(logging_dir) / "myagent-summary.json").write_text(
                json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        status = summary.get("status")
        return AgentResult(
            total_input_tokens=int(summary.get("promptTokens", 0)),
            total_output_tokens=int(summary.get("completionTokens", 0)),
            # 只能用 loopsbench.harness.models.FailureMode 里的值，别的字符串会让框架当场抛
            failure_mode="none" if status == "COMPLETED"
            else "agent_timeout" if status == "TERMINATED"
            else ("agent_startup_error" if not summary else "unknown_agent_error"),
        )
