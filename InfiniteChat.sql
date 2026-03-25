/*
 Navicat Premium Dump SQL

 Source Server         : IM
 Source Server Type    : MySQL
 Source Server Version : 80408 (8.4.8)
 Source Host           : localhost:49152
 Source Schema         : InfiniteChat

 Target Server Type    : MySQL
 Target Server Version : 80408 (8.4.8)
 File Encoding         : 65001

 Date: 22/03/2026 11:00:53
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for ai_assistant
-- ----------------------------
DROP TABLE IF EXISTS `ai_assistant`;
CREATE TABLE `ai_assistant`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `assistant_id` bigint NOT NULL COMMENT 'AI助手ID',
  `assistant_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'AI助手名称',
  `assistant_avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'AI助手头像',
  `model_type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT 'AI模型类型',
  `personality` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'AI人设JSON',
  `context_limit` int NULL DEFAULT 10 COMMENT '上下文记忆条数',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 0=禁用 1=启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI助手配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for balance_log
-- ----------------------------
DROP TABLE IF EXISTS `balance_log`;
CREATE TABLE `balance_log`  (
  `balance_log_id` bigint NOT NULL COMMENT '流水ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `amount` decimal(10, 2) NOT NULL COMMENT '变动金额 (正数表示增加，负数表示减少)',
  `type` tinyint NOT NULL COMMENT '类型：1-发红包扣款，2-抢红包入账，3-红包退回',
  `related_id` bigint NOT NULL COMMENT '关联业务ID (比如具体的红包ID)',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变动时间',
  PRIMARY KEY (`balance_log_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_related_id`(`related_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户资金变动流水表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for contact
-- ----------------------------
DROP TABLE IF EXISTS `contact`;
CREATE TABLE `contact`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `contact_id` bigint NOT NULL COMMENT '联系人ID',
  `contact_type` tinyint NOT NULL DEFAULT 0 COMMENT '联系人类型 0=普通用户 1=AI助手 2=群聊机器人 3=系统通知',
  `remark` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注名',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 0=已删除 1=正常 2=已拉黑',
  `is_pinned` tinyint NOT NULL DEFAULT 0 COMMENT '是否置顶 0=否 1=是',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_contact`(`user_id` ASC, `contact_id` ASC) USING BTREE,
  INDEX `idx_contact_id`(`contact_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 9 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '联系人表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for contact_request
-- ----------------------------
DROP TABLE IF EXISTS `contact_request`;
CREATE TABLE `contact_request`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `from_user_id` bigint NOT NULL COMMENT '申请人ID',
  `to_user_id` bigint NOT NULL COMMENT '接收人ID',
  `remark` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0=待处理 1=已同意 2=已拒绝',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_to_user_status`(`to_user_id` ASC, `status` ASC) USING BTREE,
  INDEX `idx_from_to`(`from_user_id` ASC, `to_user_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '好友申请表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for im_friend
-- ----------------------------
DROP TABLE IF EXISTS `im_friend`;
CREATE TABLE `im_friend`  (
  `id` bigint NOT NULL COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID (拥有者)',
  `friend_id` bigint NOT NULL COMMENT '好友ID',
  `remark` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '好友备注名',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态: 0-申请中, 1-正常, 2-拉黑, 3-删除',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_friend`(`user_id` ASC, `friend_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户好友关系表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for im_message
-- ----------------------------
DROP TABLE IF EXISTS `im_message`;
CREATE TABLE `im_message`  (
  `message_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息ID (分布式雪花算法 String)',
  `client_msg_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `seq` bigint NULL DEFAULT NULL,
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `sender_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '发送者ID',
  `receiver_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '接收者ID (群聊可为空)',
  `message_type` tinyint NOT NULL COMMENT '消息类型: 1文本, 2图片, 3文件, 4视频, 5红包, 6表情包',
  `session_type` tinyint NOT NULL COMMENT '会话类型: 1单聊, 2群聊',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息内容',
  `reply_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '回复ID (非回复消息则为空)',
  `status` tinyint NULL DEFAULT 0 COMMENT '消息状态: 0正常, 1撤回, 2删除',
  `create_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发送时间 (精确到毫秒)',
  `update_time` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间',
  PRIMARY KEY (`message_id`) USING BTREE,
  UNIQUE INDEX `uk_client_msg_id`(`client_msg_id` ASC) USING BTREE,
  INDEX `idx_session_time`(`session_id` ASC, `create_time` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IM核心聊天消息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for im_msg_failover
-- ----------------------------
DROP TABLE IF EXISTS `im_msg_failover`;
CREATE TABLE `im_msg_failover`  (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `client_msg_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '客户端消息ID',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '会话ID',
  `payload` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'MQ消息完整JSON',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0=未处理 1=已重投',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `retry_count` int NOT NULL DEFAULT 0 COMMENT '已重试次数，超过最大阈值后 status 置为 -1（死信）',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_client_msg`(`client_msg_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 2035397162223616002 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '消息容灾表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for im_session
-- ----------------------------
DROP TABLE IF EXISTS `im_session`;
CREATE TABLE `im_session`  (
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `session_type` tinyint NOT NULL COMMENT '会话类型: 1-单聊, 2-群聊',
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '会话名称(群名称)',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '会话头像(群头像)',
  `creator_id` bigint NULL DEFAULT NULL COMMENT '创建人ID(群主)',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`session_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IM会话(群组)基础表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for im_user_session
-- ----------------------------
DROP TABLE IF EXISTS `im_user_session`;
CREATE TABLE `im_user_session`  (
  `id` bigint NOT NULL COMMENT '主键ID',
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role_type` tinyint NULL DEFAULT 3 COMMENT '角色: 1-群主, 2-管理员, 3-普通成员',
  `status` tinyint NULL DEFAULT 1 COMMENT '状态: 1-正常, 2-被踢出, 3-主动退群',
  `create_time` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  `last_ack_seq` bigint NOT NULL DEFAULT 0 COMMENT '用户在本会话已确认收到的最大消息seq，用于重连/换设备时离线消息补齐',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_session_user`(`session_id` ASC, `user_id` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '会话与用户关联表(群成员表)' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for moment
-- ----------------------------
DROP TABLE IF EXISTS `moment`;
CREATE TABLE `moment`  (
  `moment_id` bigint NOT NULL COMMENT '动态ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '文字内容',
  `images` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '图片列表JSON',
  `like_count` int NOT NULL DEFAULT 0 COMMENT '点赞数',
  `comment_count` int NOT NULL DEFAULT 0 COMMENT '评论数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`moment_id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '朋友圈动态表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for moment_comment
-- ----------------------------
DROP TABLE IF EXISTS `moment_comment`;
CREATE TABLE `moment_comment`  (
  `comment_id` bigint NOT NULL COMMENT '评论ID',
  `moment_id` bigint NOT NULL COMMENT '动态ID',
  `user_id` bigint NOT NULL COMMENT '评论者ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '评论内容',
  `reply_to_user_id` bigint NULL DEFAULT NULL COMMENT '回复目标用户ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`comment_id`) USING BTREE,
  INDEX `idx_moment_id`(`moment_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '朋友圈评论表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for moment_like
-- ----------------------------
DROP TABLE IF EXISTS `moment_like`;
CREATE TABLE `moment_like`  (
  `like_id` bigint NOT NULL COMMENT '点赞ID',
  `moment_id` bigint NOT NULL COMMENT '动态ID',
  `user_id` bigint NOT NULL COMMENT '点赞者ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`like_id`) USING BTREE,
  UNIQUE INDEX `uk_moment_user`(`moment_id` ASC, `user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '朋友圈点赞表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for offline_message
-- ----------------------------
DROP TABLE IF EXISTS `offline_message`;
CREATE TABLE `offline_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `message_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息ID',
  `receiver_id` bigint NOT NULL COMMENT '接收者ID',
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `seq` bigint NOT NULL COMMENT '会话级序列号',
  `message_type` tinyint NOT NULL COMMENT '消息类型 1=文本 2=图片 3=语音 4=视频 5=红包',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息内容JSON',
  `sender_id` bigint NOT NULL COMMENT '发送者ID',
  `sender_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '发送者昵称',
  `sender_avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '发送者头像',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `expire_at` datetime NOT NULL COMMENT '过期时间',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态 0=未读 1=已读 2=已删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_receiver_session`(`receiver_id` ASC, `session_id` ASC) USING BTREE,
  INDEX `idx_receiver_created`(`receiver_id` ASC, `created_at` ASC) USING BTREE,
  INDEX `idx_expire`(`expire_at` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '离线消息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for red_packet
-- ----------------------------
DROP TABLE IF EXISTS `red_packet`;
CREATE TABLE `red_packet`  (
  `red_packet_id` bigint NOT NULL COMMENT '红包ID (雪花算法全局唯一)',
  `sender_id` bigint NOT NULL COMMENT '发送者用户ID',
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `red_packet_wrapper_text` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '恭喜发财，大吉大利' COMMENT '红包封面文案',
  `red_packet_type` tinyint NOT NULL COMMENT '红包类型：1-普通红包，2-拼手气红包',
  `total_amount` decimal(10, 2) NOT NULL COMMENT '红包总金额',
  `total_count` int NOT NULL COMMENT '红包总个数',
  `remaining_amount` decimal(10, 2) NOT NULL COMMENT '剩余金额',
  `remaining_count` int NOT NULL COMMENT '剩余个数',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：1-未领取完，2-已领取完，3-已过期',
  `created_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`red_packet_id`) USING BTREE,
  INDEX `idx_session_id`(`session_id` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '红包核心主表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for red_packet_receive
-- ----------------------------
DROP TABLE IF EXISTS `red_packet_receive`;
CREATE TABLE `red_packet_receive`  (
  `red_packet_receive_id` bigint NOT NULL COMMENT '记录ID (雪花算法发号)',
  `red_packet_id` bigint NOT NULL COMMENT '红包ID',
  `receiver_id` bigint NOT NULL COMMENT '领取者用户ID',
  `amount` decimal(10, 2) NOT NULL COMMENT '领取金额',
  `received_at` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  PRIMARY KEY (`red_packet_receive_id`) USING BTREE,
  UNIQUE INDEX `uk_red_packet_receiver`(`red_packet_id` ASC, `receiver_id` ASC) USING BTREE,
  INDEX `idx_receiver_id`(`receiver_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '红包领取记录表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for unread_count
-- ----------------------------
DROP TABLE IF EXISTS `unread_count`;
CREATE TABLE `unread_count`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `session_id` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL,
  `unread_count` int NOT NULL DEFAULT 0 COMMENT '未读数',
  `last_message_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最后一条消息ID',
  `last_message_time` datetime NULL DEFAULT NULL COMMENT '最后一条消息时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_user_session`(`user_id` ASC, `session_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '未读消息数表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user
-- ----------------------------
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user`  (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `user_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '头像',
  `signature` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '个性签名',
  `gender` tinyint NULL DEFAULT 0 COMMENT '性别 0=未知 1=男 2=女',
  `status` tinyint NULL DEFAULT 1 COMMENT '状态',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE INDEX `uk_phone`(`phone` ASC) USING BTREE,
  UNIQUE INDEX `uk_email`(`email` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for user_balance
-- ----------------------------
DROP TABLE IF EXISTS `user_balance`;
CREATE TABLE `user_balance`  (
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `balance` decimal(10, 2) NOT NULL DEFAULT 0.00 COMMENT '余额',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户余额表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
