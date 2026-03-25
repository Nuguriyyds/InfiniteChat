-- ============================================================
-- 消息同步拉取压测 Lua 脚本
-- 接口: GET /api/message/sync?sessionId=xxx&beginSeq=0&endSeq=50
-- 数据文件: /opt/infinitechat/send_msg_data.txt
-- 每行格式: userId|token|sessionId|receiveUserId
--
-- 用法:
--   wrk2 -t4 -c100 -d60s -R1000 -L \
--     -s /opt/infinitechat/sync_msg.lua \
--     http://127.0.0.1:10010/api/message/sync
-- ============================================================

wrk.method = "GET"

local entries = {}

function init(args)
    local f = io.open("/opt/infinitechat/send_msg_data.txt", "r")
    if not f then
        print("[ERROR] 找不到 send_msg_data.txt，请先执行 batch_login.sh")
        os.exit(1)
    end
    for line in f:lines() do
        local uid, tok, sid, rid = line:match("([^|]+)|([^|]+)|([^|]+)|([^|]+)")
        if uid and tok and sid then
            table.insert(entries, {
                userId    = uid,
                token     = tok,
                sessionId = sid
            })
        end
    end
    f:close()
    if #entries == 0 then
        print("[ERROR] send_msg_data.txt 为空")
        os.exit(1)
    end
    print("[INFO] 加载 " .. #entries .. " 条会话数据用于 sync 压测")
end

local counter = 0

function request()
    counter = counter + 1
    local e = entries[(counter % #entries) + 1]

    wrk.headers["Authorization"] = "Bearer " .. e.token
    wrk.headers["X-User-Id"]     = e.userId

    -- 模拟客户端拉取最近 50 条消息（beginSeq=0 表示从头拉）
    -- 实际场景中 beginSeq 会是客户端本地的 lastAckSeq
    local beginSeq = math.random(0, 10)
    local endSeq   = beginSeq + 50

    local path = string.format(
        "/api/message/sync?sessionId=%s&beginSeq=%d&endSeq=%d",
        e.sessionId, beginSeq, endSeq)

    return wrk.format("GET", path)
end

function done(summary, latency, requests)
    print("--------- 消息同步拉取压测结果 ---------")
    print(string.format("  请求总数:   %d", summary.requests))
    print(string.format("  平均延迟:   %.2f ms", latency.mean / 1000))
    print(string.format("  P99 延迟:   %.2f ms", latency:percentile(99) / 1000))
    print(string.format("  P999延迟:   %.2f ms", latency:percentile(99.9) / 1000))
    print(string.format("  连接错误:   %d", summary.errors.connect))
    print(string.format("  读取错误:   %d", summary.errors.read))
    print(string.format("  写入错误:   %d", summary.errors.write))
    print(string.format("  超时错误:   %d", summary.errors.timeout))
    print(string.format("  非2xx响应:  %d", summary.errors.status))
end
