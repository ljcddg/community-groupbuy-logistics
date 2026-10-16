package com.community.warehouse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存流水。（biz_no, change_type）唯一索引 —— 从数据层保证"同一业务号只扣一次"。
 */
@Data
@TableName("t_stock_log")
public class StockLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer skuId;

    private String bizNo;

    /** 1-扣减 2-回滚 */
    private Integer changeType;

    private Integer quantity;

    private LocalDateTime createTime;
}
