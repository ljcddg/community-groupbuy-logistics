package com.community.pickup.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.pickup.entity.Pickup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PickupMapper extends BaseMapper<Pickup> {

    /**
     * 原子占用容量。
     * 面试点：{@code WHERE used_capacity + ? <= capacity} 条件更新，
     * 避免并发下多个配送单同时占用导致超容。
     */
    @Update("UPDATE t_pickup SET used_capacity = used_capacity + #{delta}, update_time = NOW() " +
            "WHERE id = #{id} AND used_capacity + #{delta} <= capacity")
    int occupy(@Param("id") Long id, @Param("delta") Integer delta);

    /** 释放容量 */
    @Update("UPDATE t_pickup SET used_capacity = GREATEST(used_capacity - #{delta}, 0), update_time = NOW() " +
            "WHERE id = #{id}")
    int release(@Param("id") Long id, @Param("delta") Integer delta);
}
