-- ============================================================
-- wrk2 抢红包压测脚本
-- 接口: POST /api/message/redpacket/receive
-- Header: Authorization: Bearer <token>, X-User-Id: <userId>
-- Body:   {"redPacketId": 6000000000000}
--
-- 数据文件: /opt/infinitechat/tokens.txt (userId|token)
-- 红包范围: 6000000000000 ~ 6000000000099
--
-- 策略:
--   1. 每个线程维护独立的用户索引，轮询不同用户
--   2. 所有线程共享红包索引，从第 0 个红包开始
--   3. 每个红包 50 个名额，抢完自动切换下一个
--   4. 通过响应中的 status=2（已领完）检测红包耗尽
--
-- 用法:
--   wrk2 -t4 -c50 -d60s -R100 -L \
--     -s /opt/infinitechat/grab_redpacket.lua \
--     http://127.0.0.1:10010/api/message/redpacket/receive
-- ============================================================

wrk.method  = "POST"
wrk.headers["Content-Type"] = "application/json"

-- 全局数据
local users = {}        -- {userId, token} 数组
local user_count = 0
local rp_base_id = 6000000000000
local rp_max_index = 99 -- 0~99 共 100 个红包

-- 每线程状态
local thread_idx = 0
local rp_index = 0      -- 当前红包索引（所有线程共享递增）

function setup(thread)
    thread:set("tid", thread_idx)
    thread_idx = thread_idx + 1
end

function init(args)
    -- 加载 tokens.txt
    local f = io.open("/opt/infinitechat/tokens.txt", "r")
    if not f then
        print("[ERROR] 无法打开 /opt/infinitechat/tokens.txt，请先执行 batch_login.sh")
        os.exit(1)
    end
    for line in f:lines() do
        local uid, token = line:match("^(%d+)|(.+)$")
        if uid and token then
            users[#users + 1] = { userId = uid, token = token }
        end
    end
    f:close()
    user_count = #users

    if user_count == 0 then
        print("[ERROR] tokens.txt 为空")
        os.exit(1)
    end

    print(string.format("[Thread %d] 加载 %d 个用户, 红包范围 %d ~ %d",
        tid, user_count, rp_base_id, rp_base_id + rp_max_index))
end

-- 请求计数器（线程内）
local req_counter = 0

function request()
    -- 轮询用户（每个请求换一个用户，模拟不同人抢）
    req_counter = req_counter + 1
    local user_idx = ((req_counter - 1 + tid * 37) % user_count) + 1
    local user = users[user_idx]

    -- 当前红包 ID
    local current_rp_id = rp_base_id + rp_index

    wrk.headers["Authorization"] = "Bearer " .. user.token
    wrk.headers["X-User-Id"] = user.userId
    wrk.body = '{"redPacketId":' .. current_rp_id .. '}'

    return wrk.format(nil)
end

function response(status, headers, body)
    -- 检测红包已抢完（status=2 表示 CLAIMED），切换下一个
    -- 响应格式: {"code":200,"data":{"amount":null,"status":2}}
    if body and body:find('"status":2') then
        if rp_index < rp_max_index then
            rp_index = rp_index + 1
            if rp_index % 10 == 0 then
                print(string.format("[Thread %d] 切换到红包 #%d (id=%d)",
                    tid, rp_index, rp_base_id + rp_index))
            end
        end
    end
end

function done(summary, latency, requests)
    print("--------- 抢红包压测结果 ---------")
    print(string.format("总请求数: %d", summary.requests))
    print(string.format("总耗时:   %.2fs", summary.duration / 1000000))
    print(string.format("QPS:      %.2f", summary.requests / (summary.duration / 1000000)))
    print(string.format("P50:      %.2fms", latency:percentile(50) / 1000))
    print(string.format("P99:      %.2fms", latency:percentile(99) / 1000))
    print(string.format("P999:     %.2fms", latency:percentile(99.9) / 1000))
    print(string.format("错误数:   %d", summary.errors.status + summary.errors.connect + summary.errors.read + summary.errors.write + summary.errors.timeout))
    print(string.format("最终红包索引: %d (id=%d)", rp_index, rp_base_id + rp_index))
end
