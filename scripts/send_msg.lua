-- ============================================================
-- Single chat send load test script for wrk/wrk2
-- Data file: /opt/infinitechat/send_msg_data.txt
-- One line format: userId|token|sessionId|receiveUserId
-- ============================================================

wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"

local entries = {}
local threads = {}
local counter = 0
thread_id = 0

function setup(thread)
    thread:set("thread_id", #threads + 1)
    table.insert(threads, thread)
end

function init(args)
    math.randomseed(os.time() + (thread_id * 100003))

    local f = io.open("/opt/infinitechat/send_msg_data.txt", "r")
    if not f then
        print("[ERROR] send_msg_data.txt not found, run batch_login.sh first")
        os.exit(1)
    end

    for line in f:lines() do
        local uid, tok, sid, rid = line:match("([^|]+)|([^|]+)|([^|]+)|([^|]+)")
        if uid and tok and sid and rid then
            table.insert(entries, {
                userId = uid,
                token = tok,
                sessionId = sid,
                receiveUserId = rid
            })
        end
    end
    f:close()

    if #entries == 0 then
        print("[ERROR] send_msg_data.txt is empty, check batch_login.sh result")
        os.exit(1)
    end

    print("[INFO] loaded " .. #entries .. " user-session rows")
end

function request()
    counter = counter + 1
    local e = entries[(counter % #entries) + 1]

    wrk.headers["Authorization"] = "Bearer " .. e.token
    wrk.headers["X-User-Id"] = e.userId

    -- Use second-level timestamp + actual wrk thread id + sender + per-thread
    -- counter to avoid clientMsgId collisions under high concurrency.
    local clientMsgId = string.format("lt_%d_t%d_u%s_c%d",
        os.time(), thread_id, e.userId, counter)

    local body = string.format(
        '{"clientMsgId":"%s","sessionId":"%s","sessionType":1,"type":1,"receiveUserId":%s,"body":{"content":"load test msg %d"}}',
        clientMsgId, e.sessionId, e.receiveUserId, counter)

    wrk.body = body
    return wrk.format(nil)
end

function done(summary, latency, requests)
    local errors = summary.errors
    print("--------- send message load test summary ---------")
    print(string.format("  total requests: %d", summary.requests))
    print(string.format("  avg latency: %.2f ms", latency.mean / 1000))
    print(string.format("  p99 latency: %.2f ms", latency:percentile(99) / 1000))
    print(string.format("  p999 latency: %.2f ms", latency:percentile(99.9) / 1000))
    print(string.format("  connect errors: %d", errors.connect))
    print(string.format("  read errors: %d", errors.read))
    print(string.format("  write errors: %d", errors.write))
    print(string.format("  timeout errors: %d", errors.timeout))
    print(string.format("  non-2xx responses: %d", errors.status))
end
