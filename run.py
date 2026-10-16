#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键启动 / 停止 本项目的 5 个 Spring Boot 服务。
用法：
  python run.py start        启动全部（后台）
  python run.py stop         停止全部
  python run.py status       查看端口状态
  python run.py start pickup 只启动指定服务
  python run.py logs order   查看某服务日志尾部
"""
import os
import subprocess
import sys
import socket
import time
import signal

PROJECT = r"D:/idea/work/Spring/CommunityGroupBuyLogistics"
JAVA = r"D:/idea/java/jdk17/jdk-17.0.12/bin/java.exe"
LOG_DIR = os.path.join(PROJECT, "logs")
PID_DIR = os.path.join(PROJECT, ".run")

# name -> (port, jar 相对路径)
# 注意 gateway 用 8090：本机 8080 被 steamwebhelper.exe 占用，换成 8090 才起得来。
SERVICES = {
    "pickup":    (8104, "logistics-pickup/target/logistics-pickup-1.0.0.jar"),
    "warehouse": (8103, "logistics-warehouse/target/logistics-warehouse-1.0.0.jar"),
    "delivery":  (8102, "logistics-delivery/target/logistics-delivery-1.0.0.jar"),
    "order":     (8101, "logistics-order/target/logistics-order-1.0.0.jar"),
    "gateway":   (8090, "logistics-gateway/target/logistics-gateway-1.0.0.jar"),
}
# 启动顺序：先被依赖的服务，最后网关
START_ORDER = ["pickup", "warehouse", "delivery", "order", "gateway"]


def port_open(port, host="127.0.0.1", timeout=1.0):
    s = socket.socket()
    s.settimeout(timeout)
    try:
        s.connect((host, port))
        return True
    except Exception:
        return False
    finally:
        s.close()


def start(names):
    os.makedirs(LOG_DIR, exist_ok=True)
    os.makedirs(PID_DIR, exist_ok=True)
    for name in names:
        if name not in SERVICES:
            print(f"  [skip] 未知服务 {name}")
            continue
        port, jar = SERVICES[name]
        if port_open(port):
            print(f"  [skip] {name} 已在运行 (port {port})")
            continue
        jar_abs = os.path.join(PROJECT, jar)
        if not os.path.exists(jar_abs):
            print(f"  [ERR ] {name} jar 不存在，请先编译: {jar_abs}")
            continue
        log_file = os.path.join(LOG_DIR, f"{name}.log")
        out = open(log_file, "w", encoding="utf-8", errors="replace")
        # 显式传 --server.port：命令行参数优先级最高，
        # 可压过环境变量（如某些 shell 环境注入的 SERVER__PORT）对端口的覆盖。
        p = subprocess.Popen(
            [JAVA, "-Dfile.encoding=UTF-8", "-jar", jar_abs, f"--server.port={port}"],
            cwd=PROJECT, stdout=out, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS,
        )
        with open(os.path.join(PID_DIR, f"{name}.pid"), "w") as f:
            f.write(str(p.pid))
        print(f"  [start] {name} pid={p.pid} port={port} log={log_file}")


def stop():
    for name in SERVICES:
        pid_file = os.path.join(PID_DIR, f"{name}.pid")
        if os.path.exists(pid_file):
            pid = open(pid_file).read().strip()
            try:
                subprocess.run(["taskkill", "/F", "/T", "/PID", pid],
                               capture_output=True)
                print(f"  [stop ] {name} pid={pid}")
            except Exception as e:
                print(f"  [err  ] {name}: {e}")
            os.remove(pid_file)
        else:
            print(f"  [skip ] {name} 无 pid 记录")


def status():
    for name, (port, _) in SERVICES.items():
        print(f"  {name:10} port {port}  {'UP' if port_open(port) else 'DOWN'}")


def logs(name, n=60):
    p = os.path.join(LOG_DIR, f"{name}.log")
    if not os.path.exists(p):
        print("无日志")
        return
    lines = open(p, encoding="utf-8", errors="replace").read().splitlines()
    print("\n".join(lines[-n:]))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "start":
        names = sys.argv[2:] if len(sys.argv) > 2 else START_ORDER
        start(names)
    elif cmd == "stop":
        stop()
    elif cmd == "status":
        status()
    elif cmd == "logs":
        logs(sys.argv[2] if len(sys.argv) > 2 else "delivery")
    else:
        print(__doc__)
