-- ============================================================================
-- 日程插件服务的独立库
--
-- 为什么单独建库而不是塞进 life_mind_agent：
-- 本服务是「插件」，将来可以整体下架、单独迁库；数据与对话记录没有外键关系，
-- 混在一个库里只会让下架时更难剥。
--
-- 执行顺序：本文件 → create_schedule_item.sql → create_schedule_reminder.sql → create_memo.sql
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `life_mind_schedule`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;
