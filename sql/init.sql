-- =====================================================================
-- 社区团购物流配送系统 —— 初始化脚本
-- 数据库：community_logistics
-- 说明：本脚本可重复执行（CREATE TABLE IF NOT EXISTS）
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `community_logistics`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `community_logistics`;

-- ---------------------------------------------------------------------
-- 订单服务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_order` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`     VARCHAR(64)  NOT NULL COMMENT '订单号',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `pickup_id`    BIGINT       NOT NULL COMMENT '自提点ID',
    `pickup_name`  VARCHAR(64)           DEFAULT NULL COMMENT '自提点名称',
    `item_count`   INT          NOT NULL DEFAULT 1 COMMENT '商品件数',
    `weight_gram`  INT          NOT NULL DEFAULT 0 COMMENT '总重量(克)',
    `status`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0待截单 1已截单 2已生成配送单 3已取消',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_pickup_status` (`pickup_id`, `status`),
    KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='团购订单';

-- 本地消息表：与业务同事务写入，事务提交后投递 MQ；投递失败可补偿重投
CREATE TABLE IF NOT EXISTS `t_local_message` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `msg_id`           VARCHAR(64)  NOT NULL COMMENT '消息唯一ID',
    `topic`            VARCHAR(128) NOT NULL,
    `tag`              VARCHAR(64)           DEFAULT NULL,
    `biz_key`          VARCHAR(128)          DEFAULT NULL COMMENT '业务键',
    `payload`          TEXT         NOT NULL COMMENT 'JSON消息体',
    `status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2发送失败',
    `retry_count`      INT          NOT NULL DEFAULT 0,
    `next_retry_time`  DATETIME              DEFAULT NULL,
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id` (`msg_id`),
    KEY `idx_status_retry` (`status`, `next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表(最终一致)';

-- ---------------------------------------------------------------------
-- 物流服务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_delivery` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `delivery_no`    VARCHAR(64)  NOT NULL COMMENT '配送单号',
    `batch_no`       VARCHAR(64)           DEFAULT NULL COMMENT '截单批次号',
    `pickup_id`      BIGINT       NOT NULL COMMENT '自提点ID',
    `pickup_name`    VARCHAR(64)           DEFAULT NULL,
    `item_count`     INT          NOT NULL DEFAULT 0,
    `weight_gram`    INT          NOT NULL DEFAULT 0,
    `driver_id`      BIGINT                DEFAULT NULL,
    `driver_name`    VARCHAR(32)           DEFAULT NULL,
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '见 DeliveryStatusEnum',
    `route_plan`     VARCHAR(512)          DEFAULT NULL COMMENT '规划顺序(自提点ID逗号拼接)',
    `total_distance` INT                   DEFAULT NULL COMMENT '总里程(米)',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_delivery_no` (`delivery_no`),
    KEY `idx_status` (`status`),
    KEY `idx_batch` (`batch_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送单';

CREATE TABLE IF NOT EXISTS `t_delivery_trace` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `delivery_id` BIGINT       NOT NULL,
    `delivery_no` VARCHAR(64)  NOT NULL,
    `from_status` TINYINT               DEFAULT NULL,
    `to_status`   TINYINT      NOT NULL,
    `remark`      VARCHAR(255)          DEFAULT NULL,
    `operator`    VARCHAR(64)           DEFAULT NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_delivery_id` (`delivery_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送轨迹';

CREATE TABLE IF NOT EXISTS `t_delivery_exception` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `delivery_id`    BIGINT       NOT NULL,
    `delivery_no`    VARCHAR(64)  NOT NULL,
    `pickup_id`      BIGINT                DEFAULT NULL,
    `exception_type` TINYINT      NOT NULL COMMENT '见 ExceptionTypeEnum',
    `handle_plan`    VARCHAR(1024)         DEFAULT NULL COMMENT '责任链算出的处理方案JSON',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已处理 3已升级',
    `need_reship`    TINYINT(1)   NOT NULL DEFAULT 0,
    `reship_count`   INT          NOT NULL DEFAULT 0,
    `escalate_round` INT          NOT NULL DEFAULT 0,
    `remark`         VARCHAR(255)          DEFAULT NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_delivery_id` (`delivery_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送异常单';

-- 消费幂等表(msg_id 唯一索引是幂等的第二道防线)
CREATE TABLE IF NOT EXISTS `t_consume_record` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `msg_id`         VARCHAR(64)  NOT NULL,
    `topic`          VARCHAR(128)          DEFAULT NULL,
    `consumer_group` VARCHAR(128)          DEFAULT NULL,
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_msg_id_group` (`msg_id`, `consumer_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ消费幂等表';

-- ---------------------------------------------------------------------
-- 仓储服务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_stock` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `sku_id`      INT         NOT NULL COMMENT 'SKU ID',
    `sku_name`    VARCHAR(64)          DEFAULT NULL,
    `total`       INT         NOT NULL DEFAULT 0 COMMENT '总库存',
    `locked`      INT         NOT NULL DEFAULT 0 COMMENT '锁定库存',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sku_id` (`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存';

-- 库存流水：每次扣减留痕，也是幂等的业务依据
CREATE TABLE IF NOT EXISTS `t_stock_log` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `sku_id`      INT         NOT NULL,
    `biz_no`      VARCHAR(64) NOT NULL COMMENT '业务号(幂等键)',
    `change_type` TINYINT     NOT NULL COMMENT '1扣减 2回滚',
    `quantity`    INT         NOT NULL,
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_biz_no_type` (`biz_no`, `change_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水';

-- ---------------------------------------------------------------------
-- 自提点服务
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `t_pickup` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `pickup_name`     VARCHAR(64)  NOT NULL,
    `address`         VARCHAR(128)          DEFAULT NULL,
    `capacity`        INT          NOT NULL DEFAULT 100 COMMENT '容量上限(件)',
    `used_capacity`   INT          NOT NULL DEFAULT 0 COMMENT '已占用(件)',
    `longitude`       DECIMAL(10,6)         DEFAULT NULL,
    `latitude`        DECIMAL(10,6)         DEFAULT NULL,
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自提点';

-- =====================================================================
-- 初始化数据
-- =====================================================================
INSERT INTO `t_stock` (`sku_id`, `sku_name`, `total`, `locked`) VALUES
    (1001, '山东红富士苹果 5斤装', 1000, 0),
    (1002, '云南高原小番茄 3斤装', 800,  0),
    (1003, '内蒙古锡盟羊肉卷 500g', 500, 0)
ON DUPLICATE KEY UPDATE `sku_name` = VALUES(`sku_name`);

INSERT INTO `t_pickup` (`id`, `pickup_name`, `address`, `capacity`, `used_capacity`, `longitude`, `latitude`) VALUES
    (1, '阳光花园自提点',   '阳光花园小区东门',   100, 30, 116.404269, 39.913164),
    (2, '翠竹苑自提点',     '翠竹苑3号楼下',      80,  75, 116.412345, 39.918234),
    (3, '万科城自提点',     '万科城北区便利店',   120, 40, 116.398765, 39.905678),
    (4, '龙湖天街自提点',   '龙湖天街B1层',       150, 20, 116.421234, 39.921345),
    (5, '金地格林自提点',   '金地格林小镇西侧',    60, 55, 116.431234, 39.910234)
ON DUPLICATE KEY UPDATE `pickup_name` = VALUES(`pickup_name`);

-- 便于演示：给自提点 1、3 预置 3 笔团购订单
INSERT INTO `t_order` (`order_no`, `user_id`, `pickup_id`, `pickup_name`, `item_count`, `weight_gram`, `status`) VALUES
    ('OD20260301001', 2001, 1, '阳光花园自提点', 2, 2500, 0),
    ('OD20260301002', 2002, 1, '阳光花园自提点', 1, 1200, 0),
    ('OD20260301003', 2003, 1, '阳光花园自提点', 3, 3000, 0),
    ('OD20260301004', 2004, 3, '万科城自提点',   2, 1800, 0),
    ('OD20260301005', 2005, 3, '万科城自提点',   1, 900,  0)
ON DUPLICATE KEY UPDATE `update_time` = NOW();
