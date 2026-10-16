package com.community.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表 —— 订单服务发 MQ 前先落库，保证"业务操作 + 发消息"的原子性。
 *
 * <p>面试点（替代 Seata 的方案）：先本地事务写好业务数据 + 消息表(status=待发送)，
 * 事务提交后再投递 MQ；投递成功更新 status=已发送。
 * 若投递失败，由定时任务扫描"待发送/发送失败"的消息补偿重投 —— 至少一次投递 + 消费端幂等 = 最终一致。
 */
@Data
@TableName("t_local_message")
public class LocalMessage implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID（消费端据此幂等） */
    private String msgId;

    private String topic;

    private String tag;

    private String bizKey;

    /** JSON 消息体 */
    private String payload;

    /** 0-待发送 1-已发送 2-发送失败 */
    private Integer status;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
