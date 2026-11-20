-- ============================================================================
-- 迁移：通知表增加「是否需要弹窗提醒」标记
--
-- 目标库：life_mind_message
-- 执行时机：一次性，随本次发布执行；幂等，可重复执行
-- ============================================================================
--
-- 为什么需要这个字段
-- ----------------
-- 当前通知只有两个接口（分页查、标已读），前端不知道有新消息，用户必须
-- 自己打开通知列表刷新才发现，一条 09:00 的提醒可能 10:30 才被看到。
--
-- 现在只做站内提醒（不接短信/邮件/APNs）：前端轮询未读通知，遇到
-- need_popup=1 的直接弹窗，把「打开才看到」变成「到点就打断」。
--
-- 为什么它和 level 不重复
-- ----------------------
-- level 表达「这件事有多要紧」，need_popup 表达「要不要打断用户当前的操作」。
-- 两者不总是同向：系统公告可能 level=1（要紧）但不该弹窗；
-- 日程提醒可能 level=0（不紧急）但必须弹，因为到点这件事本身就是全部意义。
-- 所以弹窗单独一个开关，由生产方按业务语义决定，不由级别推导。
--
-- 轮询查询形如：
--   WHERE user_id = ? AND is_read = 0 AND need_popup = 1
-- 走现有的 idx_notification_user_read(user_id, is_read)，need_popup 只做残余过滤。
-- 单个用户的通知量很小，不额外加索引。
-- ============================================================================

USE `life_mind_message`;

-- MySQL 8 的 ADD COLUMN 不支持 IF NOT EXISTS，用 information_schema 判一次再动态执行
SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'life_mind_message'
      AND TABLE_NAME = 'notification'
      AND COLUMN_NAME = 'need_popup'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `notification`
        ADD COLUMN `need_popup` tinyint NOT NULL DEFAULT ''0''
        COMMENT ''是否需要弹窗提醒：0-否 1-是；只决定网页内是否打断用户，不涉及短信/邮件等外部渠道''
        AFTER `level`',
    'SELECT ''need_popup 已存在，跳过'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 存量数据不回填：已有的 4 条 system 通知属于「看一眼就行」的系统消息，
-- 默认值 0 正好符合语义。日程提醒的数据从今往后由 schedule 服务显式设 1。
-- ----------------------------------------------------------------------------

SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'life_mind_message'
  AND TABLE_NAME = 'notification'
ORDER BY ORDINAL_POSITION;
