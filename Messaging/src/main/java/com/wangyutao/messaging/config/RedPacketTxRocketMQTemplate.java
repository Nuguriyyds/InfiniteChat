package com.wangyutao.messaging.config;

import org.apache.rocketmq.spring.annotation.ExtRocketMQTemplateConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

/**
 * 抢红包事务消息专用生产者：独立 producer group，与默认 {@code rocketMQTemplate}（普通消息）隔离。
 * <p>
 * 配置项见 {@code rocketmq.red-packet-tx-producer-group}；NameServer 等沿用 {@code rocketmq.name-server}。
 * 说明见：<a href="https://github.com/apache/rocketmq-spring/wiki">RocketMQ Spring Wiki</a>
 */
@ExtRocketMQTemplateConfiguration(
        group = "${rocketmq.red-packet-tx-producer-group:im-red-packet-receive-tx-group}"
)
public class RedPacketTxRocketMQTemplate extends RocketMQTemplate {
}
