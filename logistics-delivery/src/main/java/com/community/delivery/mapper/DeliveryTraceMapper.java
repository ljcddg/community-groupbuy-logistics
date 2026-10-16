package com.community.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.delivery.entity.DeliveryTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DeliveryTraceMapper extends BaseMapper<DeliveryTrace> {

    @Select("SELECT * FROM t_delivery_trace WHERE delivery_id = #{deliveryId} ORDER BY id")
    List<DeliveryTrace> selectByDeliveryId(@Param("deliveryId") Long deliveryId);
}
