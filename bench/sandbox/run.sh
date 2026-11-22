#!/bin/sh
# 沙箱的 Linux 冒烟：真的 bubblewrap + 出网代理，每一条打印 PASS / FAIL。
# 在仓库根目录跑：sh bench/sandbox/run.sh   （要 Docker；容器要 --privileged，bwrap 要建命名空间）
set -e
cd "$(dirname "$0")/../.."
mvn -q package -DskipTests
rm -rf bench/sandbox/classes && javac -encoding UTF-8 --release 17 -cp target/myagent.jar -d bench/sandbox/classes bench/sandbox/SandboxSmoke.java
docker build --pull=false -q -t myagent-sandbox-smoke bench/sandbox >/dev/null
# Windows 的 Git Bash 下要 Windows 路径、并关掉 MSYS 的路径改写
REPO=$(pwd -W 2>/dev/null || pwd)
MSYS_NO_PATHCONV=1 docker run --rm --privileged -v "$REPO:/repo" myagent-sandbox-smoke sh -c '
  mkdir -p /srv && echo SMOKE-OK > /srv/index.html
  (cd /srv && python3 -m http.server 8099 --bind 127.0.0.1 >/dev/null 2>&1 &)
  sleep 1
  java -cp /repo/target/myagent.jar:/repo/bench/sandbox/classes SandboxSmoke'
