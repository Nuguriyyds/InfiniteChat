-- ============================================================
-- InfiniteChat 红包压测数据生成脚本
-- 目标: 创建群聊 Session + 群聊红包（count=50），让多人并发抢
-- 前提: generate_data.sql 已执行，1000 个测试用户已入库
-- 用法: mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat < generate_redpacket_data.sql
-- ============================================================

SET NAMES utf8mb4;
SET autocommit = 0;
SET unique_checks = 0;
SET foreign_key_checks = 0;
SET cte_max_recursion_depth = 5000;

-- ============================================================
-- 辅助数字表
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS seq_1000;
CREATE TEMPORARY TABLE seq_1000 (n INT NOT NULL, PRIMARY KEY(n));
INSERT INTO seq_1000 (n)
WITH RECURSIVE cte AS (
    SELECT 0 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 999
)
SELECT n FROM cte;

-- ============================================================
-- Step 1: 创建 10 个压测群聊 Session
-- session_id: group_redpacket_0 ~ group_redpacket_9
-- ============================================================
INSERT IGNORE INTO im_session (session_id, session_type, name, creator_id, create_time, update_time)
SELECT
    CONCAT('group_redpacket_', n) AS session_id,
    2 AS session_type,
    CONCAT('红包压测群_', n) AS name,
    1000000001 AS creator_id,
    NOW(),
    NOW()
FROM seq_1000
WHERE n < 10;

COMMIT;
SELECT '[Step 1] 10 个群聊 Session 创建完成' AS progress;

-- ============================================================
-- Step 2: 把前 500 个测试用户加入每个群
-- 每个群 500 人，10 个群共 5000 条 im_user_session
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS seq_10;
CREATE TEMPORARY TABLE seq_10 (n INT NOT NULL, PRIMARY KEY(n));
INSERT INTO seq_10 (n) VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9);

INSERT IGNORE INTO im_user_session (id, session_id, user_id, role_type, status, create_time, last_ack_seq)
SELECT
    6000000000000 + (g.n * 1000) + u.n AS id,
    CONCAT('group_redpacket_', g.n) AS session_id,
    1000000001 + u.n AS user_id,
    CASE WHEN u.n = 0 THEN 1 ELSE 3 END AS role_type,
    1 AS status,
    NOW() AS create_time,
    0 AS last_ack_seq
FROM seq_1000 u
CROSS JOIN seq_10 g
WHERE u.n < 500;

COMMIT;
SELECT '[Step 2] 群成员关系创建完成（10群 × 500人）' AS progress;

-- ============================================================
-- Step 3: 清理旧的压测红包数据（幂等）
-- ============================================================
DELETE FROM red_packet_receive WHERE red_packet_id BETWEEN 6000000000000 AND 6000000000099;
DELETE FROM balance_log WHERE related_id BETWEEN 6000000000000 AND 6000000000099;
DELETE FROM red_packet WHERE red_packet_id BETWEEN 6000000000000 AND 6000000000099;
COMMIT;
SELECT '[Step 3] 旧压测红包数据已清理' AS progress;

-- ============================================================
-- Step 4: 生成 100 个群聊拼手气红包
-- red_packet_id: 6000000000000 ~ 6000000000099
-- 每个红包 50 个名额，总金额 50 元（均值 1 元/个）
-- 分布在 10 个群中，每群 10 个红包
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS seq_100;
CREATE TEMPORARY TABLE seq_100 (n INT NOT NULL, PRIMARY KEY(n));
INSERT INTO seq_100 (n)
WITH RECURSIVE cte AS (
    SELECT 0 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 99
)
SELECT n FROM cte;

INSERT INTO red_packet
    (red_packet_id, sender_id, session_id, red_packet_wrapper_text,
     red_packet_type, total_amount, total_count, remaining_amount, remaining_count,
     status, created_at, updated_at)
SELECT
    6000000000000 + n                           AS red_packet_id,
    1000000001 + (n * 7 % 1000)                 AS sender_id,
    CONCAT('group_redpacket_', n % 10)          AS session_id,
    '恭喜发财，大吉大利',
    2,          -- 拼手气红包
    50.00,      -- 总金额 50 元
    50,         -- 50 个名额
    50.00,      -- 剩余金额 = 总金额（未被抢）
    50,         -- 剩余个数 = 50
    1,          -- status=1 未领取完
    NOW(),
    NOW()
FROM seq_100;

COMMIT;
SELECT CONCAT('[Step 4] 群聊红包生成完成，共 ', ROW_COUNT(), ' 个') AS progress;

-- ============================================================
-- 清理
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS seq_1000;
DROP TEMPORARY TABLE IF EXISTS seq_100;
DROP TEMPORARY TABLE IF EXISTS seq_10;

SET unique_checks = 1;
SET foreign_key_checks = 1;
SET autocommit = 1;

SELECT '========== 红包压测数据生成完成 ==========' AS result;
SELECT '群聊 Session: group_redpacket_0 ~ group_redpacket_9（每群 500 人）' AS info;
SELECT '红包 ID 范围: 6000000000000 ~ 6000000000099（100 个拼手气红包）' AS info;
SELECT '每个红包: 50 个名额，总金额 50 元' AS info;
SELECT '下一步: 执行 redis_warmup_redpacket.sh 预热 Redis' AS info;
