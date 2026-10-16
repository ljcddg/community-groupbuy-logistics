package com.community.delivery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.delivery.entity.Delivery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DeliveryMapper extends BaseMapper<Delivery> {

    /**
     * 状态机 CAS 流转：仅当当前状态 == expectStatus 时才更新。
     *
     * <p>面试点：这是"乐观锁"+ "防并发脏写"的核心。
     * 返回 0 表示状态已被别人改过（或已流转到目标状态），调用方据此返回"正被处理"。
     * 用 status 当版本号的好处是无需额外 version 字段，且业务语义天然一致。
     */
    @Update("UPDATE t_delivery SET status = #{toStatus}, " +
            "driver_id = COALESCE(#{driverId}, driver_id), " +
            "driver_name = COALESCE(#{driverName}, driver_name), " +
            "update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectStatus}")
    int casStatus(@Param("id") Long id,
                  @Param("expectStatus") Integer expectStatus,
                  @Param("toStatus") Integer toStatus,
                  @Param("driverId") Long driverId,
                  @Param("driverName") String driverName);

    /** 无司机信息的 CAS（异常/完成等流转用） */
    @Update("UPDATE t_delivery SET status = #{toStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectStatus}")
    int casStatusOnly(@Param("id") Long id,
                      @Param("expectStatus") Integer expectStatus,
                      @Param("toStatus") Integer toStatus);

    /** 更新路线规划结果 */
    @Update("UPDATE t_delivery SET route_plan = #{routePlan}, total_distance = #{totalDistance}, " +
            "update_time = NOW() WHERE id = #{id}")
    int updateRoute(@Param("id") Long id,
                    @Param("routePlan") String routePlan,
                    @Param("totalDistance") Integer totalDistance);

    /** 按批次查询配送单 */
    @Select("SELECT * FROM t_delivery WHERE batch_no = #{batchNo} ORDER BY id")
    List<Delivery> selectByBatch(@Param("batchNo") String batchNo);

    /** 查询待配送的单（用于路线规划） */
    @Select("SELECT * FROM t_delivery WHERE status IN (0, 1) ORDER BY pickup_id LIMIT 50")
    List<Delivery> selectPendingForRoute();
}
