# WS Load Test

This project already has good `wrk2` coverage for HTTP endpoints. This note adds a matching WebSocket-side test so we can observe:

1. How many online connections a node can hold steadily.
2. Whether heartbeats stay healthy under load.
3. Whether clients reconnect cleanly after a Netty node restart.
4. Whether HTTP `send_msg.lua` traffic is actually delivered to online WS clients.

## Prerequisites

1. Generate login tokens with the existing batch script:

```bash
bash /opt/infinitechat/batch_login.sh 500
```

2. Install the Node dependency once on the pressure host:

```bash
cd /opt/infinitechat
npm install ws
```

3. Upload `ws_load_test.js` to the same directory as `tokens.txt`.

## Scenario A: Steady long-lived connections

Bring up a batch of online users, keep heartbeats alive, and watch server CPU / memory / connection count:

```bash
node /opt/infinitechat/ws_load_test.js \
  --tokens=/opt/infinitechat/tokens.txt \
  --url=ws://127.0.0.1:10010/api/v1/chat/message \
  --connections=500 \
  --ramp-up-ms=60000 \
  --heartbeat-ms=30000 \
  --report-ms=5000
```

Recommended observations:

- `ss -s`
- `top`
- `jstat -gcutil <rtc-pid> 2000`
- Redis route TTL refresh on `im:route:{userId}`

## Scenario B: Push throughput with real online users

Keep the WS load script running, then start your existing HTTP send-message pressure:

```bash
wrk2 -t4 -c100 -d60s -R500 -L -s /opt/infinitechat/send_msg.lua http://127.0.0.1:10010/api/message/send
```

What to watch:

- `push=` count in WS load output keeps rising
- `ackSent=` rises as pushed messages are acknowledged
- no abnormal disconnect spike during HTTP pressure

## Scenario C: Fault-recovery / restart test

Start the WS script with reconnect enabled:

```bash
node /opt/infinitechat/ws_load_test.js \
  --tokens=/opt/infinitechat/tokens.txt \
  --connections=300 \
  --ramp-up-ms=30000 \
  --report-ms=2000 \
  --reconnect=true
```

Then restart the RTC node during the run.

Suggested checks:

1. Before restart: note `active=300/300`.
2. Restart the RTC process.
3. Observe temporary disconnects and reconnect attempts.
4. Confirm `active` returns close to the original level.
5. Run `send_msg.lua` again and confirm `push=` continues increasing after recovery.

This is a good resume/interview statement template:

> Built a WS load harness for long-lived connection and restart recovery tests; validated client heartbeat stability, reconnect behavior, and online push continuity under concurrent HTTP message traffic.

## Notes

- The server uses single-login semantics, so do not open multiple concurrent sockets for the same user.
- Current WS auth is `?token=` in the URL, not `Authorization` header.
- The load script is meant to complement `wrk2`, not replace the HTTP-side pressure tests.
