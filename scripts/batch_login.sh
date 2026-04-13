#!/bin/bash
# ============================================================
# Batch login helper for pressure tests.
# Output files:
#   tokens.txt       -> userId|token|nettyUrl
#   session_ids.txt  -> sessionId
#   send_msg_data.txt -> userId|token|sessionId|receiveUserId
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

echo "========== Batch login start =========="
echo "Target users: $USER_COUNT"

echo "[Step 1] Querying test user phones from MySQL..."
PHONES=$($MYSQL_CMD -e "SELECT phone FROM user WHERE user_id BETWEEN 1000000001 AND 1000000001 + $USER_COUNT - 1 ORDER BY user_id")

if [ -z "$PHONES" ]; then
    echo "[ERROR] No test users found, please run generate_data.sql first"
    exit 1
fi

echo "[Step 2] Logging in users one by one..."
SUCCESS=0
FAIL=0

while IFS= read -r PHONE; do
    RESP=$(curl -s -X POST "$LOGIN_URL" \
        -H "Content-Type: application/json" \
        -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}")

    TOKEN=$(echo "$RESP" | grep -oP '"token"\s*:\s*"[^"]*"' | head -1 | grep -oP '"[^"]*"$' | tr -d '"')
    USER_ID=$(echo "$RESP" | grep -oP '"userId"\s*:\s*"?[0-9]+"?' | head -1 | grep -oP '[0-9]+')
    NETTY_URL=$(echo "$RESP" | grep -oP '"nettyUrl"\s*:\s*"[^"]*"' | head -1 | grep -oP '"[^"]*"$' | tr -d '"')

    if [ -z "$TOKEN" ] || [ -z "$USER_ID" ] || [ -z "$NETTY_URL" ]; then
        FAIL=$((FAIL + 1))
        echo "  [FAIL] phone=$PHONE resp=$RESP"
        continue
    fi

    SUCCESS=$((SUCCESS + 1))
    echo "${USER_ID}|${TOKEN}|${NETTY_URL}" >> "$TOKENS_FILE"

    if [ $((SUCCESS % 20)) -eq 0 ]; then
        echo "  Logged in $SUCCESS users..."
    fi

    sleep 0.05
done <<< "$PHONES"

echo "[Step 2 Done] success=$SUCCESS fail=$FAIL"

LOGGED_IDS=$(awk -F'|' '{print $1}' "$TOKENS_FILE" | tr '\n' ',' | sed 's/,$//')
if [ -z "$LOGGED_IDS" ]; then
    echo "[ERROR] No users logged in successfully"
    exit 1
fi

echo "[Step 3] Querying single-chat sessions..."
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
echo "[Step 3 Done] user-session mappings=$SESSION_COUNT"

echo "[Step 4] Building send_msg_data.txt..."
> "$OUTPUT_DIR/send_msg_data.txt"

while IFS=$'\t' read -r SID MY_ID FRIEND_ID; do
    TOKEN_LINE=$(grep "^${MY_ID}|" "$TOKENS_FILE" | head -1)
    if [ -n "$TOKEN_LINE" ]; then
        TOKEN=$(echo "$TOKEN_LINE" | cut -d'|' -f2)
        echo "${MY_ID}|${TOKEN}|${SID}|${FRIEND_ID}" >> "$OUTPUT_DIR/send_msg_data.txt"
    fi
done < "$OUTPUT_DIR/user_sessions.txt"

SEND_COUNT=$(wc -l < "$OUTPUT_DIR/send_msg_data.txt")
echo "[Step 4 Done] send_msg_data rows=$SEND_COUNT"

awk -F'|' '{print $3}' "$OUTPUT_DIR/send_msg_data.txt" | sort -u > "$SESSION_FILE"
SYNC_COUNT=$(wc -l < "$SESSION_FILE")
echo "[Step 5 Done] session_ids count=$SYNC_COUNT"

echo ""
echo "========== Batch login complete =========="
echo "tokens.txt:        $TOKENS_FILE ($SUCCESS users)"
echo "send_msg_data.txt: $OUTPUT_DIR/send_msg_data.txt ($SEND_COUNT rows)"
echo "session_ids.txt:   $SESSION_FILE ($SYNC_COUNT sessions)"
