-- ============================================================
-- InfiniteChat 测试数据生成脚本（纯 SQL，无需 Python）
-- 目标: 1000 用户 + contact好友 + 2万条聊天消息 + 红包数据
-- 用法: mysql -h47.97.100.24 -P49152 -uroot -p'gK3T9n%q2M@j7Z4' InfiniteChat < generate_data.sql
-- ============================================================

SET NAMES utf8mb4;
SET autocommit = 0;
SET unique_checks = 0;
SET foreign_key_checks = 0;
SET cte_max_recursion_depth = 25000;

-- ============================================================
-- Step 0: 预计算 BCrypt 密文（Test@123456，cost=10）
-- ⚠️ 使用前必须替换！在项目里跑一次:
--   System.out.println(cn.hutool.crypto.digest.BCrypt.hashpw("Test@123456"));
-- 把输出粘贴到下面替换掉占位符
-- ============================================================
SET @BCRYPT_PWD = '$2a$10$20BA2YhnyLqii//k.5HSzuhqdcu5aNwV/H3pcez3SX1QH3DsxV1xG';

-- ============================================================
-- 辅助数字表（0~999）
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS seq_1000;
CREATE TEMPORARY TABLE seq_1000 (n INT NOT NULL, PRIMARY KEY(n));
INSERT INTO seq_1000 (n)
WITH RECURSIVE cte AS (
    SELECT 0 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 999
)
SELECT n FROM cte;

-- ============================================================
-- Step 1: 生成 1000 个用户
-- user_id: 1000000001 ~ 1000001000
-- ============================================================
INSERT IGNORE INTO `user`
    (user_id, user_name, password, email, phone, avatar, signature, gender, status, created_at, updated_at)
SELECT
    1000000001 + n                                          AS user_id,
    CONCAT('testuser_', LPAD(n + 1, 4, '0'))               AS user_name,
    @BCRYPT_PWD                                             AS password,
    CONCAT('test', n + 1, '_', FLOOR(RAND()*9000+1000), '@loadtest.com') AS email,
    CONCAT('138', LPAD(FLOOR(RAND() * 100000000), 8, '0')) AS phone,
    NULL                                                    AS avatar,
    ELT(1 + FLOOR(RAND()*5), '热爱生活', '代码改变世界', '今天也要加油', '随遇而安', '压测战士') AS signature,
    FLOOR(RAND() * 3)                                       AS gender,
    1                                                       AS status,
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*60) DAY)          AS created_at,
    NOW()                                                   AS updated_at
FROM seq_1000;

COMMIT;
SELECT CONCAT('[Step 1] 用户生成完成，共 ', ROW_COUNT(), ' 条') AS progress;

-- ============================================================
-- Step 2: 生成用户余额（每人 100~500 元）
-- ============================================================
INSERT IGNORE INTO user_balance (user_id, balance, created_at, updated_at)
SELECT
    1000000001 + n,
    ROUND(100 + RAND() * 400, 2),
    NOW(),
    NOW()
FROM seq_1000;

COMMIT;
SELECT '[Step 2] 用户余额生成完成' AS progress;

-- ============================================================
-- Step 3: 生成好友关系（写入 contact 表，双向写入）
-- 策略：每个用户和相邻的 10 个用户互为好友，保证全覆盖
-- 1000 用户 × 10 好友 × 双向 = 约 20000 条 contact 记录
-- ============================================================

-- 好友偏移量表（1~10）
DROP TEMPORARY TABLE IF EXISTS seq_offset;
CREATE TEMPORARY TABLE seq_offset (off INT NOT NULL);
INSERT INTO seq_offset VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9),(10);

-- 生成单向好友对（A -> B，其中 B = A + offset，环形取模保证不越界）
DROP TEMPORARY TABLE IF EXISTS tmp_contacts;
CREATE TEMPORARY TABLE tmp_contacts (
    user_id BIGINT NOT NULL,
    contact_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, contact_id)
);

INSERT IGNORE INTO tmp_contacts (user_id, contact_id)
SELECT
    1000000001 + s.n                                          AS user_id,
    1000000001 + ((s.n + o.off) % 1000)                       AS contact_id
FROM seq_1000 s
CROSS JOIN seq_offset o;

-- 补充反向关系（B -> A）
DROP TEMPORARY TABLE IF EXISTS tmp_contacts_rev;
CREATE TEMPORARY TABLE tmp_contacts_rev AS SELECT contact_id AS user_id, user_id AS contact_id FROM tmp_contacts;

INSERT IGNORE INTO tmp_contacts (user_id, contact_id)
SELECT user_id, contact_id FROM tmp_contacts_rev;

DROP TEMPORARY TABLE IF EXISTS tmp_contacts_rev;

-- 写入 contact 表（id 自增，无需手动指定）
INSERT IGNORE INTO contact (user_id, contact_id, contact_type, remark, status, is_pinned, created_at, updated_at)
SELECT
    user_id,
    contact_id,
    0,      -- contact_type = 普通用户
    NULL,
    1,      -- status = 正常
    0,
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*30) DAY),
    NOW()
FROM tmp_contacts;

COMMIT;
SELECT CONCAT('[Step 3] contact 好友关系生成完成，共 ', ROW_COUNT(), ' 条') AS progress;

DROP TEMPORARY TABLE IF EXISTS tmp_contacts;
DROP TEMPORARY TABLE IF EXISTS seq_offset;

-- ============================================================
-- Step 4: 生成单聊 Session
-- session_id 格式: 小userId_大userId
-- ============================================================
INSERT IGNORE INTO im_session (session_id, session_type, creator_id, create_time, update_time)
SELECT DISTINCT
    CONCAT(LEAST(user_id, contact_id), '_', GREATEST(user_id, contact_id)) AS session_id,
    1 AS session_type,
    LEAST(user_id, contact_id) AS creator_id,
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*30) DAY),
    NOW()
FROM contact
WHERE user_id BETWEEN 1000000001 AND 1000001000
  AND contact_id BETWEEN 1000000001 AND 1000001000
  AND user_id < contact_id;

COMMIT;
SELECT CONCAT('[Step 4] Session 生成完成，共 ', ROW_COUNT(), ' 条') AS progress;

-- ============================================================
-- Step 5: 为每个 Session 的两个用户创建 im_user_session 记录
-- ============================================================
INSERT IGNORE INTO im_user_session (id, session_id, user_id, role_type, status, create_time, last_ack_seq)
SELECT
    3000000000000 + ROW_NUMBER() OVER () AS id,
    session_id,
    creator_id AS user_id,
    3, 1, create_time, 0
FROM im_session
WHERE session_type = 1
  AND creator_id BETWEEN 1000000001 AND 1000001000;

INSERT IGNORE INTO im_user_session (id, session_id, user_id, role_type, status, create_time, last_ack_seq)
SELECT
    4000000000000 + ROW_NUMBER() OVER () AS id,
    s.session_id,
    CAST(SUBSTRING_INDEX(s.session_id, '_', -1) AS UNSIGNED) AS user_id,
    3, 1, s.create_time, 0
FROM im_session s
WHERE s.session_type = 1
  AND s.creator_id BETWEEN 1000000001 AND 1000001000;

COMMIT;
SELECT '[Step 5] im_user_session 生成完成' AS progress;

-- ============================================================
-- Step 6: 生成 20000 条聊天消息
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS tmp_sessions;
CREATE TEMPORARY TABLE tmp_sessions (
    idx INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL
);
INSERT INTO tmp_sessions (session_id)
SELECT session_id FROM im_session
WHERE session_type = 1
  AND creator_id BETWEEN 1000000001 AND 1000001000
LIMIT 5000;

SET @session_count = (SELECT COUNT(*) FROM tmp_sessions);

DROP TEMPORARY TABLE IF EXISTS seq_20000;
CREATE TEMPORARY TABLE seq_20000 (n INT NOT NULL);
INSERT INTO seq_20000 (n)
WITH RECURSIVE cte AS (
    SELECT 0 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 19999
)
SELECT n FROM cte;

-- 先生成带 seq 的中间结果（ROW_NUMBER 按会话内递增）
INSERT IGNORE INTO im_message
    (message_id, client_msg_id, seq, session_id, sender_id, receiver_id,
     message_type, session_type, content, reply_id, status, create_time, update_time)
SELECT
    CONCAT(UNIX_TIMESTAMP(NOW()), LPAD(d.n, 8, '0'))  AS message_id,
    CONCAT(HEX(FLOOR(RAND()*4294967295)), HEX(FLOOR(RAND()*4294967295)), LPAD(d.n, 6, '0')) AS client_msg_id,
    d.seq_num,
    d.session_id,
    -- sender/receiver 用 n%2 统一决定方向，避免自己发给自己
    CASE WHEN d.n % 2 = 0
        THEN SUBSTRING_INDEX(d.session_id, '_', 1)
        ELSE SUBSTRING_INDEX(d.session_id, '_', -1)
    END AS sender_id,
    CASE WHEN d.n % 2 = 0
        THEN SUBSTRING_INDEX(d.session_id, '_', -1)
        ELSE SUBSTRING_INDEX(d.session_id, '_', 1)
    END AS receiver_id,
    1, 1,
    ELT(1 + FLOOR(RAND()*10),
        '你好，最近怎么样？', '在吗？', '明天有空吗？',
        '哈哈哈哈', '好的收到', '稍等一下',
        '帮我看一下代码', '压测消息', '下午开会吗？', '今天吃什么？'
    ) AS content,
    NULL, 0,
    -- 时间按 seq 递增：基准 30 天前，每条 +10 分钟，保证会话内时间有序
    DATE_ADD(DATE_SUB(NOW(), INTERVAL 30 DAY), INTERVAL d.seq_num * 10 MINUTE) AS create_time,
    DATE_ADD(DATE_SUB(NOW(), INTERVAL 30 DAY), INTERVAL d.seq_num * 10 MINUTE) AS update_time
FROM (
    SELECT
        s.n,
        ts.session_id,
        ROW_NUMBER() OVER (PARTITION BY ts.session_id ORDER BY s.n) AS seq_num
    FROM seq_20000 s
    JOIN tmp_sessions ts ON ts.idx = (s.n % @session_count) + 1
) d;

COMMIT;
SELECT CONCAT('[Step 6] 消息生成完成，共 ', ROW_COUNT(), ' 条') AS progress;

-- ============================================================
-- Step 7: 生成红包测试数据
-- 200 个待抢红包（status=1 未领取完），用于压测抢红包接口
-- ============================================================

DROP TEMPORARY TABLE IF EXISTS seq_200;
CREATE TEMPORARY TABLE seq_200 (n INT NOT NULL);
INSERT INTO seq_200 (n)
WITH RECURSIVE cte AS (
    SELECT 0 AS n UNION ALL SELECT n + 1 FROM cte WHERE n < 199
)
SELECT n FROM cte;

-- 单聊红包：count=1，金额 1~10 元随机
INSERT IGNORE INTO red_packet
    (red_packet_id, sender_id, session_id, red_packet_wrapper_text,
     red_packet_type, total_amount, total_count, remaining_amount, remaining_count,
     status, created_at, updated_at)
SELECT
    5000000000000 + rp.n                                    AS red_packet_id,
    1000000001 + (rp.n * 5 % 1000)                          AS sender_id,
    ts.session_id,
    '恭喜发财，大吉大利',
    1,              -- 普通红包（单聊只有 1 个，拼手气无意义）
    rp.amount,      -- 总金额
    1,              -- 单聊 count=1
    rp.amount,      -- 剩余金额 = 总金额（未被抢）
    1,              -- 剩余个数 = 1
    1,              -- status=1 未领取完
    DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*60) MINUTE),
    NOW()
FROM (
    SELECT n, ROUND(1 + RAND() * 9, 2) AS amount FROM seq_200
) rp
JOIN tmp_sessions ts ON ts.idx = (rp.n % @session_count) + 1;

COMMIT;
SELECT CONCAT('[Step 7] 红包生成完成，共 ', ROW_COUNT(), ' 条') AS progress;

-- ============================================================
-- 清理
-- ============================================================
DROP TEMPORARY TABLE IF EXISTS seq_1000;
DROP TEMPORARY TABLE IF EXISTS seq_200;
DROP TEMPORARY TABLE IF EXISTS seq_20000;
DROP TEMPORARY TABLE IF EXISTS tmp_sessions;

SET unique_checks = 1;
SET foreign_key_checks = 1;
SET autocommit = 1;

SELECT '========== 全部完成 ==========' AS result;
SELECT 'user_id 范围: 1000000001 ~ 1000001000' AS info;
SELECT '测试账号: testuser_0001 ~ testuser_1000，密码: Test@123456' AS info;
SELECT '红包ID范围: 5000000000000 ~ 5000000000199（200个待抢红包）' AS info;
SELECT '提示: 压测抢红包前需用 redis-cli 预热红包数据，见 redis_warmup.sh' AS info;
