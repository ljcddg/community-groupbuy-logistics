package com.community.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.order.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 查询指定批次内、尚未生成配送单的订单（按自提点分组前的原始数据） */
    @Select("SELECT * FROM t_order WHERE status = 0 ORDER BY pickup_id, id")
    List<Order> selectPendingOrders();

    /** 按自提点聚合：统计每个自提点待处理订单的件数与重量 */
    @Select("SELECT pickup_id AS pickupId, " +
            "       MAX(pickup_name) AS pickupName, " +
            "       COUNT(*) AS itemCount, " +
            "       SUM(weight_gram) AS weightGram " +
            "FROM t_order " +
            "WHERE status = 0 " +
            "GROUP BY pickup_id")
    List<PickupAggregate> aggregateByPickup();

    /** 聚合结果投影 */
    class PickupAggregate {
        private Long pickupId;
        private String pickupName;
        private Integer itemCount;
        private Integer weightGram;

        public Long getPickupId() { return pickupId; }
        public void setPickupId(Long pickupId) { this.pickupId = pickupId; }
        public String getPickupName() { return pickupName; }
        public void setPickupName(String pickupName) { this.pickupName = pickupName; }
        public Integer getItemCount() { return itemCount; }
        public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }
        public Integer getWeightGram() { return weightGram; }
        public void setWeightGram(Integer weightGram) { this.weightGram = weightGram; }
    }
}
