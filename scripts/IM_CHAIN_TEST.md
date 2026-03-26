# IM Chain Test

This script turns the existing HTTP and WS pressure helpers into a smaller closed-loop validator for the IM core path.

It verifies:

1. `/api/message/send` returns `messageId` and `seq`.
2. An online receiver really gets the pushed message over WebSocket.
3. The same message becomes visible through `/api/message/sync`.
4. An offline receiver can later fetch it through `/api/offline/pull`.
5. Optional: MySQL records exist in `im_message` and `offline_message`.

## Prerequisites

1. Start the full IM environment.
2. Generate test users and login data with the existing script:

```bash
bash /opt/infinitechat/batch_login.sh 200
```

That script produces:

- `tokens.txt`: `userId|token`
- `send_msg_data.txt`: `userId|token|sessionId|receiveUserId`

3. Install the single Node dependency if you have not already:

```bash
cd /opt/infinitechat
npm install ws
```

## Basic usage

Only test the online path:

```bash
node /opt/infinitechat/scripts/im_chain_test.js \
  --scenario=online \
  --tokens=/opt/infinitechat/tokens.txt \
  --send-data=/opt/infinitechat/send_msg_data.txt
```

Run both online and offline checks:

```bash
node /opt/infinitechat/scripts/im_chain_test.js \
  --scenario=both \
  --tokens=/opt/infinitechat/tokens.txt \
  --send-data=/opt/infinitechat/send_msg_data.txt \
  --gateway-base=http://127.0.0.1:10010 \
  --ws-url=ws://127.0.0.1:9101/api/v1/chat/message
```

Pick a later sender/receiver pair if the default one is dirty:

```bash
node /opt/infinitechat/scripts/im_chain_test.js \
  --scenario=both \
  --case-index=10
```

By default, the formal contract is HTTP through Gateway plus WS direct to RTC. If you want to validate the retained Gateway WS proxy path instead, override `--ws-url=ws://127.0.0.1:10010/api/v1/chat/message`.

## Optional MySQL verification

If the pressure host has `mysql` CLI installed, the script can also verify:

- `im_message`
- `offline_message`

Example:

```bash
node /opt/infinitechat/scripts/im_chain_test.js \
  --scenario=both \
  --mysql-verify=true \
  --mysql-host=172.30.233.168 \
  --mysql-port=49152 \
  --mysql-user=root \
  --mysql-password='gK3T9n%q2M@j7Z4' \
  --mysql-database=InfiniteChat
```

## Output

The script exits `0` on success and prints a JSON summary like:

```json
{
  "ok": true,
  "reports": [
    {
      "name": "online",
      "senderId": "1000000001",
      "receiverId": "1000000002",
      "sessionId": "single_...",
      "messageId": "msg_...",
      "clientMsgId": "chain_online_...",
      "seq": 123,
      "checks": ["http_send", "ws_delivery", "sync_visible"]
    },
    {
      "name": "offline",
      "senderId": "1000000003",
      "receiverId": "1000000004",
      "sessionId": "single_...",
      "messageId": "msg_...",
      "clientMsgId": "chain_offline_...",
      "seq": 456,
      "checks": ["http_send", "sync_visible", "offline_pull"]
    }
  ]
}
```

## Why this is useful

Your existing `wrk2` scripts tell you whether an interface is fast.

This script tells you whether a message actually completed the business path:

- HTTP accepted
- WS delivered
- MQ async persistence visible
- offline recovery works

That makes it a good regression gate before and after Gateway, MQ, or RTC performance changes.
