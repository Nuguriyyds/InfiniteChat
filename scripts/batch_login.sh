#!/bin/bash
# ============================================================
# 批量登录脚本 —— 为压测准备多用户 Token
# 输出文件:
#   tokens.txt     -> 每行: userId|token|sessionId (供 send_msg.lua 使用)
#   session_ids.txt -> 每行: sessionId (供 sync_msg.lua 使用)
#
# 用法: bash batch_login.sh [用户数量，默认100]
# 前提: generate_data.sql 已执行，1000 个测试用户已入库
# ============================================================

GATEWAY="http://127.0.0.1:10010"
LOGIN_URL="$GATEWAY/api/v1/user/noToken/loginPwd"
PASSWORD="Test@123456"
OUTPUT_DIR="/opt/infinitechat"
TOKENS_FILE="$OUTPUT_DIR/tokens.txt"
SESSION_FILE="$OUTPUT_DIR/session_ids.txt"

USER_COUNT=${1:-100}

MYSQL_HOST="172.30.233.168"
MYSQL_PORT=49152
MYSQL_USER="root"
MYSQL_PASS="gK3T9n%q2M@j7Z4"
MYSQL_DB="InfiniteChat"
MYSQL_CMD="mysql -h$MYSQL_HOST -P$MYSQL_PORT -u$MYSQL_USER -p$MYSQL_PASS $MYSQL_DB --batch --skip-column-names"

mkdir -p "$OUTPUT_DIR"
> "$TOKENS_FILE"
> "$SESSION_FILE"

echo "========== 批量登录开始 =========="
echo "目标用户数: $USER_COUNT"

# Step 1: 从 MySQL 查出测试用户的手机号
echo "[Step 1] 从 MySQL 查询测试用户手机号..."
PHONES=$($MYSQL_CMD -e "SELECT phone FROM user WHERE user_id BETWEEN 1000000001 AND 1000000001 + $USER_COUNT - 1 ORDER BY user_id")

if [ -z "$PHONES" ]; then
    echo "[错误] 未查到测试用户，请先执行 generate_data.sql"
    exit 1
fi

# Step 2: 逐个登录，提取 token 和 userId
echo "[Step 2] 开始逐个登录..."
SUCCESS=0
FAIL=0

while IFS= read -r PHONE; do
    RESP=$(curl -s -X POST "$LOGIN_URL" \
        -H "Content-Type: application/json" \
        -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}")

    # 从响应中提取 token 和 userId（适配常见的 JSON 响应格式）
    TOKEN=$(echo "$RESP" | grep -oP '"token"\s*:\s*"[^"]*"' | head -1 | grep -oP '"[^"]*"$' | tr -d '"')
    USER_ID=$(echo "$RESP" | grep -oP '"userId"\s*:\s*"?[0-9]+"?' | head -1 | grep -oP '[0-9]+')

    if [ -z "$TOKEN" ] || [ -z "$USER_ID" ]; then
        FAIL=$((FAIL + 1))
        echo "  [失败] phone=$PHONE resp=$RESP"
        continue
    fi

    SUCCESS=$((SUCCESS + 1))

    # 写入 tokens.txt
    echo "${USER_ID}|${TOKEN}" >> "$TOKENS_FILE"

    if [ $((SUCCESS % 20)) -eq 0 ]; then
        echo "  已登录 $SUCCESS 个用户..."
    fi

    # 控制速率，避免把登录接口打挂（BCrypt 很吃 CPU）
    sleep 0.05
done <<< "$PHONES"

echo "[Step 2 完成] 成功: $SUCCESS, 失败: $FAIL"

# Step 3: 从 MySQL 查出这些用户的会话 ID（用于 send_msg 和 sync）
echo "[Step 3] 从 MySQL 查询会话数据..."

# 提取已登录成功的 userId 列表
LOGGED_IDS=$(awk -F'|' '{print $1}' "$TOKENS_FILE" | tr '\n' ',' | sed 's/,$//')

if [ -z "$LOGGED_IDS" ]; then
    echo "[错误] 没有成功登录的用户"
    exit 1
fi

# 查出这些用户参与的单聊会话，同时带上对方的 userId
# 输出格式: sessionId|userId|friendId
$MYSQL_CMD -e "
SELECT
    s.session_id,
    us1.user_id AS my_id,
    us2.user_id AS friend_id
FROM im_session s
JOIN im_user_session us1 ON us1.session_id = s.session_id AND us1.user_id IN ($LOGGED_IDS)
JOIN im_user_session us2 ON us2.session_id = s.session_id AND us2.user_id != us1.user_id
WHERE s.session_type = 1
ORDER BY us1.user_id
LIMIT 5000
" > "$OUTPUT_DIR/user_sessions.txt"

SESSION_COUNT=$(wc -l < "$OUTPUT_DIR/user_sessions.txt")
echo "[Step 3 完成] 查到 $SESSION_COUNT 条用户-会话映射"

# Step 4: 生成 send_msg 专用数据文件
# 格式: userId|token|sessionId|receiveUserId
echo "[Step 4] 生成压测数据文件..."

> "$OUTPUT_DIR/send_msg_data.txt"

while IFS=$'\t' read -r SID MY_ID FRIEND_ID; do
    # 从 tokens.txt 找到该用户的 token
    TOKEN_LINE=$(grep "^${MY_ID}|" "$TOKENS_FILE" | head -1)
    if [ -n "$TOKEN_LINE" ]; then
        TOKEN=$(echo "$TOKEN_LINE" | cut -d'|' -f2)
        echo "${MY_ID}|${TOKEN}|${SID}|${FRIEND_ID}" >> "$OUTPUT_DIR/send_msg_data.txt"
    fi
done < "$OUTPUT_DIR/user_sessions.txt"

SEND_COUNT=$(wc -l < "$OUTPUT_DIR/send_msg_data.txt")
echo "[Step 4 完成] send_msg_data.txt 共 $SEND_COUNT 条"

# Step 5: 生成 sync 专用数据文件（sessionId 去重）
awk -F'|' '{print $3}' "$OUTPUT_DIR/send_msg_data.txt" | sort -u > "$SESSION_FILE"
SYNC_COUNT=$(wc -l < "$SESSION_FILE")
echo "[Step 5 完成] session_ids.txt 共 $SYNC_COUNT 个会话"

echo ""
echo "========== 批量登录完成 =========="
echo "tokens.txt:        $TOKENS_FILE ($SUCCESS 个用户)"
echo "send_msg_data.txt: $OUTPUT_DIR/send_msg_data.txt ($SEND_COUNT 条)"
echo "session_ids.txt:   $SESSION_FILE ($SYNC_COUNT 个会话)"
echo ""
echo "接下来可以执行压测:"
echo "  wrk2 -t4 -c100 -d60s -R500 -L -s /opt/infinitechat/send_msg.lua http://127.0.0.1:10010/api/message/send"
echo "  wrk2 -t4 -c100 -d60s -R1000 -L -s /opt/infinitechat/sync_msg.lua http://127.0.0.1:10010/api/message/sync"
