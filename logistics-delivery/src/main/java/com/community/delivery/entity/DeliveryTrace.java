package com.community.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 配送轨迹（每次状态流转追加一条，不可变）。
 */
@Data
@TableName("t_delivery_trace")
public class DeliveryTrace implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;

    private String deliveryNo;

    private Integer fromStatus;

    private Integer toStatus;

    private String remark;

    private String operator;

    private LocalDateTime createTime;
}
