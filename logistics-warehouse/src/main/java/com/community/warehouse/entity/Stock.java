package com.community.warehouse.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 库存。
 * 扣减用 {@code UPDATE t_stock SET total = total - ? WHERE sku_id = ? AND total >= ?}
 * —— 把"判断够不够"和"扣减"合并成一条原子语句，避免 check-then-act 竞态。
 */
@Data
@TableName("t_stock")
public class Stock implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer skuId;

    private String skuName;

    private Integer total;

    private Integer locked;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
