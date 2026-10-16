#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
在 Windows/Git-Bash 环境下调用 Maven 的包装脚本。
为什么需要它：
  1) Git Bash 下 mvn 脚本会报 ClassNotFoundException: plexus.classworlds.launcher.Launcher
     -> 直接用 java 启动 plexus-classworlds launcher 绕过。
  2) Bash 工具里 coreutils 不可用（tail/head/grep 缺失）-> 用 Python 做输出过滤。
用法:
  python mvnw.py <maven-goals...>            # 例如: python mvnw.py -pl logistics-order -am install
"""
import os
import subprocess
import sys

PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO = r"D:/maven_warehouse"
MAVEN_HOME = r"D:/maven/apache-maven-3.9.9"
JAVA = r"D:/idea/java/jdk17/jdk-17.0.12/bin/java.exe"
CLASSPATH = r"D:/maven/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar"


def main():
    goals = sys.argv[1:]
    cmd = [
        JAVA,
        f"-Dmaven.multiModuleProjectDirectory={PROJECT_DIR}",
        f"-Dmaven.home={MAVEN_HOME}",
        f"-Dclassworlds.conf={MAVEN_HOME}/bin/m2.conf",
        "-classpath", CLASSPATH,
        "org.codehaus.plexus.classworlds.launcher.Launcher",
        f"-Dmaven.repo.local={REPO}",
    ] + goals

    env = os.environ.copy()
    env["MSYS_NO_PATHCONV"] = "1"

    print("[mvnw] " + " ".join(goals), flush=True)
    lines = []
    proc = subprocess.Popen(
        cmd, cwd=PROJECT_DIR, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1,
    )
    for line in proc.stdout:
        lines.append(line)
        # 关键行实时打印，避免长时间无反馈
        s = line.rstrip()
        if any(k in s for k in ("ERROR", "BUILD", "Downloading", "FAILURE", "WARNING: ")):
            print(s, flush=True)
    proc.wait()

    print(f"\n[mvnw] exit={proc.returncode}, total_lines={len(lines)}")
    if proc.returncode != 0:
        print("========== 最后 80 行 ==========")
        print("".join(lines[-80:]))
    return proc.returncode


if __name__ == "__main__":
    sys.exit(main())
