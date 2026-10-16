package com.community.warehouse.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.common.exception.BizException;
import com.community.common.result.ResultCode;
import com.community.warehouse.entity.StockLog;
import com.community.warehouse.mapper.StockLogMapper;
import com.community.warehouse.mapper.StockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 库存服务。
 *
 * <p><b>面试点</b>：
 * <ol>
 *   <li><b>原子扣减</b>：{@code UPDATE ... WHERE total >= ?} 一条语句完成"判断+扣减"，
 *       靠 InnoDB 行锁保证原子性，避免超卖。</li>
 *   <li><b>业务幂等</b>：{@code t_stock_log(biz_no, change_type)} 唯一索引，
 *       同一补发单重复扣减会抛 {@link DuplicateKeyException}，捕获后返回"已处理"。
 *       这样 MQ 重投也不会重复扣库存。</li>
 *   <li><b>为什么不用 SELECT ... FOR UPDATE？</b>
 *       悲观锁会持有行锁直到事务结束，高并发下加剧锁等待；乐观扣减（条件 UPDATE）
 *       只在真正冲突时才阻塞，吞吐更好。库存这种"热点行"场景，
 *       进一步还可做"库存分段"或 Redis 预扣，面试可提。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockMapper stockMapper;
    private final StockLogMapper stockLogMapper;

    /** 扣减库存（幂等）。返回 true 表示本次真正扣减成功。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean deduct(Integer skuId, Integer quantity, String bizNo) {
        if (quantity == null || quantity <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "扣减数量必须大于 0");
        }

        // 先用流水表唯一索引拦重复（业务幂等）
        try {
            StockLog log = new StockLog();
            log.setSkuId(skuId);
            log.setBizNo(bizNo);
            log.setChangeType(1);
            log.setQuantity(quantity);
            log.setCreateTime(LocalDateTime.now());
            stockLogMapper.insert(log);
        } catch (DuplicateKeyException e) {
            log.info("库存扣减幂等命中：bizNo={} 已处理过，跳过", bizNo);
            return true;
        }

        // 原子扣减
        int affected = stockMapper.deduct(skuId, quantity);
        if (affected == 0) {
            // 库存不足，回滚本次流水（保证下次重试还能再试），并抛业务异常
            stockLogMapper.delete(new LambdaQueryWrapper<StockLog>()
                    .eq(StockLog::getBizNo, bizNo)
                    .eq(StockLog::getChangeType, 1));
            throw new BizException(ResultCode.STOCK_NOT_ENOUGH,
                    String.format("SKU[%d] 库存不足，需扣减 %d", skuId, quantity));
        }

        log.info("库存扣减成功 skuId={} qty={} bizNo={}", skuId, quantity, bizNo);
        return true;
    }

    /** 回滚库存（补发失败时补偿） */
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(Integer skuId, Integer quantity, String bizNo) {
        try {
            StockLog log = new StockLog();
            log.setSkuId(skuId);
            log.setBizNo(bizNo);
            log.setChangeType(2);
            log.setQuantity(quantity);
            log.setCreateTime(LocalDateTime.now());
            stockLogMapper.insert(log);
        } catch (DuplicateKeyException e) {
            log.info("库存回滚幂等命中：bizNo={} 已回滚过", bizNo);
            return true;
        }
        stockMapper.rollback(skuId, quantity);
        log.info("库存回滚成功 skuId={} qty={} bizNo={}", skuId, quantity, bizNo);
        return true;
    }

    public Integer query(Integer skuId) {
        Integer total = stockMapper.queryTotal(skuId);
        return total == null ? 0 : total;
    }
}
