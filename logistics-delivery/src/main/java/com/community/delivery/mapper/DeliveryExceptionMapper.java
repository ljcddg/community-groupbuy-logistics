package com.community.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.delivery.entity.DeliveryException;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DeliveryExceptionMapper extends BaseMapper<DeliveryException> {

    @Select("SELECT * FROM t_delivery_exception WHERE delivery_id = #{deliveryId} " +
            "ORDER BY id DESC LIMIT 1")
    DeliveryException selectLatestByDeliveryId(@Param("deliveryId") Long deliveryId);

    /** CAS 更新异常单状态，防重复处理（配合分布式锁） */
    @Update("UPDATE t_delivery_exception SET status = #{toStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectStatus}")
    int casStatus(@Param("id") Long id,
                  @Param("expectStatus") Integer expectStatus,
                  @Param("toStatus") Integer toStatus);
}
