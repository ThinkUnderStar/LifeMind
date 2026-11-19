-- 插件初始数据：life_mind_account.plugin
--
-- 可重复执行：已存在的 plugin_key 不做任何覆盖，
-- 避免把运营在库里改过的展示名 / 上架状态 / 默认值冲掉。
-- 新增插件时只往这里追加一行，再执行一次即可。
--
-- default_enabled 只有待办默认为 1：它是最高频的「随手记一条」，
-- 其余三个（日程 / 账单 / 健康）涉及更私密的数据，默认关，由用户主动开。
INSERT INTO `plugin` (`plugin_key`, `name`, `description`, `icon`, `version`, `status`, `default_enabled`, `sort`)
VALUES ('todo', '待办', '记录和管理待办事项', 'todo', '1.0.0', 1, 1, 10),
       ('schedule', '日程', '管理日程与提醒', 'schedule', '1.0.0', 1, 0, 20),
       ('bill', '账单', '记录收支与统计', 'bill', '1.0.0', 1, 0, 30),
       ('health', '健康', '记录睡眠、运动与身体数据', 'health', '1.0.0', 1, 0, 40)
ON DUPLICATE KEY UPDATE `plugin_key` = `plugin_key`;
