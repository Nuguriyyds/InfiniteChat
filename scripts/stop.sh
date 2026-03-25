#!/bin/bash
# ============================================================
# InfiniteChat 一键停止（在服务器上执行）
# 用法: cd /opt/infinitechat && bash stop.sh
# ============================================================

JARS=(
    "Gateway-0.0.1-SNAPSHOT.jar"
    "AuthenticationService-0.0.1-SNAPSHOT.jar"
    "Messaging-0.0.1-SNAPSHOT.jar"
    "RealTimeCommunication-0.0.1-SNAPSHOT.jar"
    "contact-0.0.1-SNAPSHOT.jar"
    "moment-0.0.1-SNAPSHOT.jar"
    "OfflineService-0.0.1-SNAPSHOT.jar"
)

echo "========== InfiniteChat 停止 =========="

for jar in "${JARS[@]}"; do
    svc="${jar%-0.0.1-SNAPSHOT.jar}"
    pid=$(pgrep -f "${jar}" 2>/dev/null)
    if [ -n "$pid" ]; then
        echo "  停止 ${svc} (PID: ${pid}) ..."
        kill "$pid"
    else
        echo "  [未运行] ${svc}"
    fi
done

# 等待 5 秒后检查是否还有残留
sleep 5
for jar in "${JARS[@]}"; do
    pid=$(pgrep -f "${jar}" 2>/dev/null)
    if [ -n "$pid" ]; then
        echo "  [强制终止] ${jar} (PID: ${pid})"
        kill -9 "$pid"
    fi
done

echo "========== 全部停止 =========="
