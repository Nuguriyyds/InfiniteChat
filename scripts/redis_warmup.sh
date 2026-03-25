#!/bin/bash
# ============================================================
# Redis 红包预热脚本
# 为 200 个单聊红包在 Redis 中创建：
#   1. 剩余个数 Key:  red_packet:{id}  = 1
#   2. 金额列表 Key:  red_packet:amounts:{id} = [金额]
#
# 用法: bash redis_warmup.sh
# 前提: 先执行 generate_data.sql 生成 MySQL 数据
# ============================================================

REDIS_HOST="47.97.100.24"
REDIS_PORT=6399
REDIS_PASS="e65K4t8w2"
REDIS_CLI="redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASS --no-auth-warning"

EXPIRE_SECONDS=86400  # 24 小时

echo "========== Redis 红包预热开始 =========="

# 从 MySQL 查出每个红包的实际金额
MYSQL_CMD="mysql -h47.97.100.24 -P49152 -uroot -pgK3T9n%q2M@j7Z4 InfiniteChat --batch --skip-column-names"

$MYSQL_CMD -e "SELECT red_packet_id, total_amount FROM red_packet WHERE red_packet_id BETWEEN 5000000000000 AND 5000000000199 AND status=1" | \
while IFS=$'\t' read -r RED_PACKET_ID AMOUNT; do
    COUNT_KEY="red_packet:count:${RED_PACKET_ID}"
    AMOUNT_KEY="red_packet:amounts:${RED_PACKET_ID}"

    # 单聊红包 count=1
    $REDIS_CLI SET "$COUNT_KEY" "1" EX $EXPIRE_SECONDS > /dev/null
    # 金额列表只有 1 个元素
    $REDIS_CLI DEL "$AMOUNT_KEY" > /dev/null
    $REDIS_CLI RPUSH "$AMOUNT_KEY" "$AMOUNT" > /dev/null
    $REDIS_CLI EXPIRE "$AMOUNT_KEY" $EXPIRE_SECONDS > /dev/null
done

echo "[完成] 红包预热完毕"

# 设置 Redis 内存上限
echo ""
echo "[配置] 设置 Redis maxmemory=768mb, 策略=allkeys-lru"
$REDIS_CLI CONFIG SET maxmemory 768mb > /dev/null
$REDIS_CLI CONFIG SET maxmemory-policy allkeys-lru > /dev/null

echo ""
echo "========== Redis 预热完成 =========="
echo "红包 ID 范围: 5000000000000 ~ 5000000000199"
echo "每个红包: count=1（单聊），金额从 MySQL 读取"
