-- ============================================================================
-- 迁移：通知表增加「事件幂等键」，抵消 MQ 重复投递
--
-- 目标库：life_mind_message
-- 执行时机：一次性，随本次发布执行；幂等，可重复执行
-- ============================================================================
--
-- 问题背景
-- --------
-- RocketMQ 只保证「至少投递一次」，同一条消息可能被投递多次：
--   * 消费成功但 ACK 丢失 → Broker 重投；
--   * 消费抛异常 → 按重试策略重投；
--   * 生产端投递超时后自行重发。
--
-- 原实现里消费者直接 save()，没有任何去重，重复消息会在通知表插两行。
-- 在只有「通知列表多一条」的时候，这只是噪音；自 need_popup 上线后，
-- 它升级成「同一条提醒弹两次窗」——用户正打字时被同类弹窗打断两回，
-- 体验上是明显的 bug。
--
-- 日程服务侧的触发路径很具体：ReminderScanTask 用 CAS(status 0->1) 抢占后
-- 投递 MQ，投递失败会 releaseOnFailure 把 status 回退到 0，下一轮扫描
-- 会再次抢占并再发一次。如果第一次其实已经发出去（只是抛了异常），
-- 队列里就会有两条内容相同、指向同一个 reminder 的消息。
--
-- 修法
-- ----
-- 1. 表加 event_id，生产方按「业务事件的唯一标识」填；
-- 2. 建唯一索引，让数据库成为去重的最终防线（应用层先查是快速路径，
--    但两个消费者实例并发时只有唯一索引能兜住）；
-- 3. 消费端把去重放在限流之前——否则被挡掉的重复消息也会消耗令牌，
--    把用户正常的通知额度吃掉。
--
-- 为什么不加 (biz_type, biz_id) 唯一键
-- ------------------------------------
-- 一个事项可以有多个提醒时刻，它们的 biz_id 都是同一个 item.id。
-- 用业务键去重会把「提前一天」和「提前 30 分钟」当成同一条，正常的
-- 第二、三条提醒会被误挡。幂等粒度必须是「哪一次提醒」。
--
-- 存量数据
-- --------
-- 已有的 4 条 system 通知没有 eventId，保持 NULL。MySQL 唯一索引允许
-- 任意多个 NULL，所以不需要回填，也不会互相冲突。
-- ============================================================================

USE `life_mind_message`;

-- ----------------------------------------------------------------------------
-- 1. 加列
-- MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS，用 information_schema 判一次
-- ----------------------------------------------------------------------------
SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'life_mind_message'
      AND TABLE_NAME = 'notification'
      AND COLUMN_NAME = 'event_id'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `notification`
        ADD COLUMN `event_id` varchar(64) DEFAULT NULL
        COMMENT ''事件幂等键：生产方保证同一次业务事件取值相同，用于抵消 MQ 至少一次投递带来的重复；手工/系统直插的通知可留空''
        AFTER `need_popup`',
    'SELECT ''event_id 已存在，跳过'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 2. 加唯一索引
-- 加索引前先确认存量数据没有重复值：一旦有重复，ALTER 会直接失败。
-- 理论上不存在（这个字段是刚加的），但重复执行本脚本或用别的路径写过数据时
-- 仍可能撞上，所以先查一次并把结果打出来。
-- ----------------------------------------------------------------------------
SELECT COUNT(*) AS duplicate_event_ids
FROM (
    SELECT `event_id`
    FROM `notification`
    WHERE `event_id` IS NOT NULL
    GROUP BY `event_id`
    HAVING COUNT(*) > 1
) t;

SET @idx_exists = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = 'life_mind_message'
      AND TABLE_NAME = 'notification'
      AND INDEX_NAME = 'uk_notification_event'
);

SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE `notification`
        ADD UNIQUE KEY `uk_notification_event` (`event_id`)',
    'SELECT ''uk_notification_event 已存在，跳过'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 3. 校验
-- ----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'life_mind_message'
  AND TABLE_NAME = 'notification'
  AND COLUMN_NAME = 'event_id';

SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'life_mind_message'
  AND TABLE_NAME = 'notification'
  AND INDEX_NAME = 'uk_notification_event';
