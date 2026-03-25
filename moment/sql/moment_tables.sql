-- 朋友圈主表
CREATE TABLE IF NOT EXISTS `moment` (
    `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID (雪花算法)',
    `user_id` BIGINT NOT NULL COMMENT '发布者用户ID',
    `content` VARCHAR(1000) DEFAULT NULL COMMENT '文字内容',
    `images` JSON DEFAULT NULL COMMENT '图片URL列表 (JSON数组)',
    `like_count` INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    `comment_count` INT NOT NULL DEFAULT 0 COMMENT '评论数',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`moment_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈主表';

-- 点赞表
CREATE TABLE IF NOT EXISTS `moment_like` (
    `like_id` BIGINT NOT NULL COMMENT '点赞ID (雪花算法)',
    `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID',
    `user_id` BIGINT NOT NULL COMMENT '点赞用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    PRIMARY KEY (`like_id`),
    UNIQUE INDEX `uk_moment_user` (`moment_id`, `user_id`),
    INDEX `idx_moment_id` (`moment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈点赞表';

-- 评论表
CREATE TABLE IF NOT EXISTS `moment_comment` (
    `comment_id` BIGINT NOT NULL COMMENT '评论ID (雪花算法)',
    `moment_id` BIGINT NOT NULL COMMENT '朋友圈ID',
    `user_id` BIGINT NOT NULL COMMENT '评论者用户ID',
    `content` VARCHAR(500) NOT NULL COMMENT '评论内容',
    `reply_to_user_id` BIGINT DEFAULT NULL COMMENT '回复目标用户ID (NULL表示直接评论)',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    PRIMARY KEY (`comment_id`),
    INDEX `idx_moment_id` (`moment_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='朋友圈评论表';
