-- ============================================================================
-- 迁移：事项表挂到重复系列上（两列 + 一个索引）
--
-- 目标库：life_mind_schedule
-- 执行时机：一次性，随本次发布执行；幂等，可重复执行
-- 前置：先跑 create_schedule_series.sql（本脚本不建表，只改列）
-- ============================================================================
--
-- 两列的含义
-- ----------
-- series_id     这条实例属于哪个系列；手写的单条事项为 NULL
-- series_index  它在系列里是第几次（从 1 开始，含锚点那次）
--
-- series_index 不参与任何查询条件，只用于展示「第 3 次 / 共 12 次」。
-- 之所以要存而不是用 row_number() 现算：实例可能被单独删掉（「这周不练了」），
-- 现算出来的序号会和用户认知错位——他心里的第 5 次不会因为删掉第 3 次就变成第 4 次。
--
-- 为什么两个列都可空
-- ------------------
-- 存量的所有事项都是「一次性」的，series_id 为 NULL 正好表达这件事。
-- 也正因为可空，现有列表/统计/逾期查询一行都不用改——它们本来就不关心这两列。
--
-- 为什么索引是 (user_id, series_id)
-- ---------------------------------
-- 唯一的使用场景是「把这个系列的实例全查出来 / 批量改」。等值匹配 user_id + series_id
-- 之后，剩下的按 base_time 排序量级很小（单系列上限 100 条），不需要再带排序键。
-- ============================================================================

USE `life_mind_schedule`;

-- MySQL 8 的 ADD COLUMN / ADD KEY 都不支持 IF NOT EXISTS，逐个用 information_schema 判

SET @col_series_id = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'life_mind_schedule'
      AND TABLE_NAME = 'schedule_item'
      AND COLUMN_NAME = 'series_id'
);

SET @ddl = IF(@col_series_id = 0,
    'ALTER TABLE `schedule_item`
        ADD COLUMN `series_id` bigint DEFAULT NULL
        COMMENT ''所属重复系列ID，指向 schedule_series.id；一次性事项为 NULL''
        AFTER `source`',
    'SELECT ''series_id 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_series_index = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'life_mind_schedule'
      AND TABLE_NAME = 'schedule_item'
      AND COLUMN_NAME = 'series_index'
);

SET @ddl = IF(@col_series_index = 0,
    'ALTER TABLE `schedule_item`
        ADD COLUMN `series_index` int DEFAULT NULL
        COMMENT ''在系列里是第几次（从 1 开始）；一次性事项为 NULL''
        AFTER `series_id`',
    'SELECT ''series_index 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_series = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'life_mind_schedule'
      AND TABLE_NAME = 'schedule_item'
      AND INDEX_NAME = 'idx_user_series'
);

SET @ddl = IF(@idx_series = 0,
    'ALTER TABLE `schedule_item` ADD KEY `idx_user_series` (`user_id`, `series_id`)',
    'SELECT ''idx_user_series 已存在，跳过'' AS msg'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 存量数据不回填：现有事项全部是一次性的，两列为 NULL 就是准确状态。
-- ----------------------------------------------------------------------------

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'life_mind_schedule'
  AND TABLE_NAME = 'schedule_item'
ORDER BY ORDINAL_POSITION;
