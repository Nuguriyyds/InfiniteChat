#!/bin/bash
# ============================================================
# Redis 红包预热脚本（群聊拼手气红包版）
# 为 100 个群聊红包在 Redis 中创建：
#   1. 库存 Key:      red_packet:count:{id}     = 50
#   2. 金额列表 Key:  red_packet:amounts:{id}   = [50个随机金额]（二倍均值法）
#   3. 防重 Set:      red_packet:received:{id}   （空 Set，自动创建）
#
# ⚠️ Key 前缀与代码 RedPacketConstants.java 完全对齐：
#   COUNT  -> red_packet:count:
#   AMOUNTS -> red_packet:amounts:
#   RECEIVED -> red_packet:received:
#   PENDING  -> red_packet:pending:
#
# 用法: bash redis_warmup_redpacket.sh
# 前提: 先执行 generate_redpacket_data.sql
# ============================================================

REDIS_HOST="127.0.0.1"
REDIS_PORT=6379
REDIS_PASS="e65K4t8w2"
REDIS_CLI="docker exec redis redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASS --no-auth-warning"

EXPIRE_SECONDS=86400  # 24 小时

# 红包参数
START_ID=6000000000000
END_ID=6000000000099
TOTAL_AMOUNT=5000    # 50.00 元 = 5000 分（用整数避免浮点误差）
TOTAL_COUNT=50
MIN_AMOUNT=1         # 0.01 元 = 1 分

echo "========== Redis 红包预热开始 =========="
echo "红包范围: $START_ID ~ $END_ID"
echo "每个红包: ${TOTAL_COUNT} 个名额, 总金额 $(echo "scale=2; $TOTAL_AMOUNT/100" | bc) 元"
echo ""

# 清理旧数据
echo "[Step 1] 清理旧的红包 Redis 数据..."
for (( id=$START_ID; id<=$END_ID; id++ )); do
    $REDIS_CLI DEL "red_packet:count:${id}" "red_packet:amounts:${id}" "red_packet:received:${id}" "red_packet:pending:${id}" > /dev/null
done
echo "[Step 1 完成]"

# 二倍均值法生成随机金额并写入 Redis
echo "[Step 2] 生成随机金额并写入 Redis（二倍均值法）..."

SUCCESS=0
for (( id=$START_ID; id<=$END_ID; id++ )); do
    COUNT_KEY="red_packet:count:${id}"
    AMOUNT_KEY="red_packet:amounts:${id}"

    # 设置库存
    $REDIS_CLI SET "$COUNT_KEY" "$TOTAL_COUNT" EX $EXPIRE_SECONDS > /dev/null

    # 用 awk 生成 50 个随机金额（二倍均值法，单位：分）
    AMOUNTS=$(awk -v total=$TOTAL_AMOUNT -v count=$TOTAL_COUNT -v min=$MIN_AMOUNT 'BEGIN {
        srand(systime() + PROCINFO["pid"]);
        remaining = total;
        for (i = 0; i < count - 1; i++) {
            avg = remaining / (count - i);
            max_val = int(avg * 2);
            if (max_val < min) max_val = min;
            amt = int(rand() * (max_val - min) + min);
            if (amt < min) amt = min;
            if (amt > remaining - (count - i - 1) * min) amt = remaining - (count - i - 1) * min;
            remaining -= amt;
            printf "%.2f\n", amt / 100;
        }
        printf "%.2f\n", remaining / 100;
    }')

    # 把 50 个金额拼成一条 RPUSH 命令（避免 docker exec + --pipe 兼容问题）
    AMOUNT_ARGS=$(echo "$AMOUNTS" | tr '\n' ' ')
    $REDIS_CLI RPUSH "$AMOUNT_KEY" $AMOUNT_ARGS > /dev/null
    $REDIS_CLI EXPIRE "$AMOUNT_KEY" $EXPIRE_SECONDS > /dev/null

    SUCCESS=$((SUCCESS + 1))
    if [ $((SUCCESS % 20)) -eq 0 ]; then
        echo "  已预热 $SUCCESS 个红包..."
    fi
done

echo "[Step 2 完成] 共预热 $SUCCESS 个红包"

# 验证
echo ""
echo "[验证] 抽样检查第一个红包..."
SAMPLE_ID=$START_ID
echo "  count key: red_packet:count:${SAMPLE_ID}"
$REDIS_CLI GET "red_packet:count:${SAMPLE_ID}"
echo "  amounts list 长度:"
$REDIS_CLI LLEN "red_packet:amounts:${SAMPLE_ID}"
echo "  amounts 前 5 个金额:"
$REDIS_CLI LRANGE "red_packet:amounts:${SAMPLE_ID}" 0 4

echo ""
echo "========== Redis 红包预热完成 =========="
echo "红包 ID 范围: $START_ID ~ $END_ID（共 $SUCCESS 个）"
echo "Redis Key 格式:"
echo "  库存:   red_packet:count:{id}"
echo "  金额:   red_packet:amounts:{id}"
echo "  防重:   red_packet:received:{id}  （抢红包时自动创建）"
echo "  暂存:   red_packet:pending:{id}   （抢红包时自动创建）"
