package com.community.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * MQ 消费幂等记录表 —— 消费端去重的落库兜底。
 *
 * <p>面试点：幂等有两层
 * <ol>
 *   <li>Redis SETNX（快，第一道闸，挡掉绝大多数重复投递）</li>
 *   <li>数据库唯一索引（准，第二道闸，防 Redis 失效/重启丢 key）</li>
 * </ol>
 * Redis 挡性能，DB 保正确，两层结合是生产标配。
 */
@Data
@TableName("t_consume_record")
public class ConsumeRecord implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 消息唯一 ID（唯一索引） */
    private String msgId;

    private String topic;

    private String consumerGroup;

    private LocalDateTime createTime;
}
