package com.community.pickup.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自提点。
 * 注意：MySQL 是权威数据源；Neo4j 只存"拓扑关系 + 剩余容量"的只读副本。
 * 一致性策略：以 MySQL 为准 -> 变更时通过 MQ/定时任务异步同步到 Neo4j（最终一致）。
 */
@Data
@TableName("t_pickup")
public class Pickup implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pickupName;

    private String address;

    /** 容量上限（件） */
    private Integer capacity;

    /** 已占用（件） */
    private Integer usedCapacity;

    private BigDecimal longitude;

    private BigDecimal latitude;

    /** 1-启用 0-停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 剩余容量（计算属性，不入库） */
    public Integer getRemainCapacity() {
        int cap = capacity == null ? 0 : capacity;
        int used = usedCapacity == null ? 0 : usedCapacity;
        return Math.max(cap - used, 0);
    }
}
