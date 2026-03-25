-- ============================================================
-- 单聊发消息压测 Lua 脚本（多用户轮询版）
-- 数据文件: /opt/infinitechat/send_msg_data.txt
-- 每行格式: userId|token|sessionId|receiveUserId
--
-- 用法:
--   wrk2 -t4 -c100 -d60s -R500 -L \
--     -s /opt/infinitechat/send_msg.lua \
--     http://127.0.0.1:10010/api/message/send
-- ============================================================

wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

-- 每行: userId|token|sessionId|receiveUserId
local entries = {}

function init(args)
    local f = io.open("/opt/infinitechat/send_msg_data.txt", "r")
    if not f then
        print("[ERROR] 找不到 send_msg_data.txt，请先执行 batch_login.sh")
        os.exit(1)
    end
    for line in f:lines() do
        local uid, tok, sid, rid = line:match("([^|]+)|([^|]+)|([^|]+)|([^|]+)")
        if uid and tok and sid and rid then
            table.insert(entries, {
                userId      = uid,
                token       = tok,
                sessionId   = sid,
                receiveUserId = rid
            })
        end
    end
    f:close()
    if #entries == 0 then
        print("[ERROR] send_msg_data.txt 为空，请检查 batch_login.sh 执行结果")
        os.exit(1)
    end
    print("[INFO] 加载 " .. #entries .. " 条用户-会话数据")
end

-- 每个线程独立的计数器，避免锁竞争
local counter = 0

function request()
    counter = counter + 1
    local e = entries[(counter % #entries) + 1]

    -- 每个请求用不同用户的 token 和 userId
    wrk.headers["Authorization"] = "Bearer " .. e.token
    wrk.headers["X-User-Id"]     = e.userId

    -- clientMsgId 必须全局唯一（幂等防重 key）
    local clientMsgId = string.format("lt_%s_%d_%d",
        e.userId, os.time(), math.random(100000, 999999))

    local body = string.format(
        '{"clientMsgId":"%s","sessionId":"%s","sessionType":1,"type":1,'
        .. '"receiveUserId":%s,"body":{"content":"压测消息%d"}}',
        clientMsgId, e.sessionId, e.receiveUserId, math.random(1, 9999))

    wrk.body = body
    return wrk.format(nil)
end

-- 压测结束后打印摘要
function done(summary, latency, requests)
    local bytes   = cycleCount or 0
    local errors  = summary.errors
    print("--------- 发消息压测结果 ---------")
    print(string.format("  请求总数:   %d", summary.requests))
    print(string.format("  平均延迟:   %.2f ms", latency.mean / 1000))
    print(string.format("  P99 延迟:   %.2f ms", latency:percentile(99) / 1000))
    print(string.format("  P999延迟:   %.2f ms", latency:percentile(99.9) / 1000))
    print(string.format("  连接错误:   %d", errors.connect))
    print(string.format("  读取错误:   %d", errors.read))
    print(string.format("  写入错误:   %d", errors.write))
    print(string.format("  超时错误:   %d", errors.timeout))
    print(string.format("  非2xx响应:  %d", errors.status))
end
