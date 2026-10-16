package com.community.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 配送异常单。记录异常类型、处理方案、升级轮次。
 */
@Data
@TableName("t_delivery_exception")
public class DeliveryException implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;

    private String deliveryNo;

    private Long pickupId;

    /** 见 ExceptionTypeEnum */
    private Integer exceptionType;

    /** 责任链算出的处理方案（JSON） */
    private String handlePlan;

    /** 0-待处理 1-处理中 2-已处理 3-已升级 */
    private Integer status;

    private Boolean needReship;

    private Integer reshipCount;

    private Integer escalateRound;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
