package com.community.delivery.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 配送单。
 * 注意 status 同时充当"乐观锁版本号"—— 状态机流转时用
 * {@code UPDATE ... WHERE id=? AND status=?} 做 CAS，天然避免 ABA。
 */
@Data
@TableName("t_delivery")
public class Delivery implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String deliveryNo;

    private String batchNo;

    private Long pickupId;

    private String pickupName;

    private Integer itemCount;

    private Integer weightGram;

    private Long driverId;

    private String driverName;

    /** 见 DeliveryStatusEnum */
    private Integer status;

    /** 规划出的配送顺序（自提点 ID 逗号拼接），由 Neo4j 图 + 贪心+2opt 计算 */
    private String routePlan;

    /** 总里程（米） */
    private Integer totalDistance;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
