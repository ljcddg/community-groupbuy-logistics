package com.community.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.order.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /** 扫描待发送 / 发送失败的消息，用于定时补偿重投 */
    @Select("SELECT * FROM t_local_message " +
            "WHERE status IN (0, 2) AND retry_count < 5 " +
            "  AND (next_retry_time IS NULL OR next_retry_time <= NOW()) " +
            "ORDER BY id LIMIT 100")
    List<LocalMessage> selectPendingMessages();
}
