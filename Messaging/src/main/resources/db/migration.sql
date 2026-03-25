-- IM Messaging 数据库变更记录
-- 按顺序执行

-- [2026-03-20] 新增 last_ack_seq 字段
-- 用途：记录每个用户在每个会话中已确认收到的最大消息 seq
-- 解决问题：App 重启/换设备登录后，客户端本地 seq 丢失，
--           服务端凭此字段告知客户端从哪条消息开始补齐，防止离线消息丢失
ALTER TABLE im_user_session
    ADD COLUMN last_ack_seq BIGINT NOT NULL DEFAULT 0
        COMMENT '用户在本会话已确认收到的最大消息seq，用于重连/换设备时离线消息补齐';

-- [2026-03-20] im_msg_failover 表新增 retry_count 字段
-- 用途：记录单条兜底消息的重试次数，超过阈值后标记为死信（status=-1），防止无限重试
ALTER TABLE im_msg_failover
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0
        COMMENT '已重试次数，超过最大阈值后 status 置为 -1（死信）';
