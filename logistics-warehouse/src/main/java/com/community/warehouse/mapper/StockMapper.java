package com.community.warehouse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.warehouse.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {

    /**
     * 原子扣减库存。
     *
     * <p><b>面试点</b>：{@code WHERE total >= #{qty}} 把"校验"和"扣减"合并为一条原子语句，
     * 避免 MySQL 默认 REPEATABLE READ 下"先查后改"的竞态（两个事务都查到够，然后都扣）。
     * 返回受影响行数 = 0 即库存不足，无需额外查询判断 —— 这是"乐观扣减"的标准写法。
     */
    @Update("UPDATE t_stock SET total = total - #{qty}, update_time = NOW() " +
            "WHERE sku_id = #{skuId} AND total >= #{qty}")
    int deduct(@Param("skuId") Integer skuId, @Param("qty") Integer qty);

    /** 回滚库存（补偿用） */
    @Update("UPDATE t_stock SET total = total + #{qty}, update_time = NOW() WHERE sku_id = #{skuId}")
    int rollback(@Param("skuId") Integer skuId, @Param("qty") Integer qty);

    @Select("SELECT total FROM t_stock WHERE sku_id = #{skuId}")
    Integer queryTotal(@Param("skuId") Integer skuId);

    @Select("SELECT * FROM t_stock WHERE sku_id = #{skuId}")
    Stock selectBySkuId(@Param("skuId") Integer skuId);
}
