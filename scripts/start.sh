#!/bin/bash
# ============================================================
# InfiniteChat 一键启动（在服务器上执行）
# 用法: cd /opt/infinitechat && bash start.sh
# ============================================================

APP_DIR="/opt/infinitechat"
LOG_DIR="${APP_DIR}/logs"
mkdir -p "$LOG_DIR"

# 服务名 -> jar名 -> JVM 参数
declare -A JARS=(
    ["Gateway"]="Gateway-0.0.1-SNAPSHOT.jar"
    ["AuthenticationService"]="AuthenticationService-0.0.1-SNAPSHOT.jar"
    ["Messaging"]="Messaging-0.0.1-SNAPSHOT.jar"
    ["RealTimeCommunication"]="RealTimeCommunication-0.0.1-SNAPSHOT.jar"
    ["contact"]="contact-0.0.1-SNAPSHOT.jar"
    ["moment"]="moment-0.0.1-SNAPSHOT.jar"
    ["OfflineService"]="OfflineService-0.0.1-SNAPSHOT.jar"
)

declare -A JVM_OPTS=(
    ["Gateway"]="-Xms128m -Xmx256m"
    ["AuthenticationService"]="-Xms256m -Xmx512m"
    ["Messaging"]="-Xms256m -Xmx512m"
    ["RealTimeCommunication"]="-Xms256m -Xmx512m"
    ["contact"]="-Xms128m -Xmx256m"
    ["moment"]="-Xms128m -Xmx256m"
    ["OfflineService"]="-Xms128m -Xmx256m"
)

# 启动顺序：Gateway 最后启动（等其他服务注册到 Nacos）
ORDER=(AuthenticationService Messaging RealTimeCommunication contact moment OfflineService Gateway)

echo "========== InfiniteChat 启动 =========="

for svc in "${ORDER[@]}"; do
    jar="${JARS[$svc]}"
    jvm="${JVM_OPTS[$svc]}"
    jar_path="${APP_DIR}/${jar}"

    if [ ! -f "$jar_path" ]; then
        echo "  [跳过] ${svc} - jar 不存在"
        continue
    fi

    # 检查是否已在运行
    pid=$(pgrep -f "${jar}" 2>/dev/null)
    if [ -n "$pid" ]; then
        echo "  [已运行] ${svc} (PID: ${pid})"
        continue
    fi

    echo -n "  启动 ${svc} ..."
    nohup java ${jvm} -jar "${jar_path}" > "${LOG_DIR}/${svc}.log" 2>&1 &
    echo " PID: $! | JVM: ${jvm}"

    # Gateway 之前的服务间隔 3 秒，让 Nacos 注册完成
    if [ "$svc" != "Gateway" ]; then
        sleep 3
    fi
done

echo ""
echo "========== 启动完成 =========="
echo "查看日志: tail -f ${LOG_DIR}/<服务名>.log"
echo "查看进程: ps aux | grep java"
