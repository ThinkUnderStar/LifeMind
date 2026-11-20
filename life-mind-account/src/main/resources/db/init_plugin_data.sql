-- 插件初始数据：life_mind_account.plugin
--
-- 可重复执行：已存在的 plugin_key 不做任何覆盖，
-- 避免把运营在库里改过的展示名 / 上架状态 / 默认值冲掉。
-- 新增插件时只往这里追加一行，再执行一次即可。
--
-- 注意：正因为「不覆盖」，改这里只对新建库生效。
-- 已有库里已存在的行不会被 UPDATE，要改存量数据必须另写迁移脚本
-- （参见 migrate_20261120_merge_todo_into_schedule.sql）。
--
-- 待办不是独立插件。它和日程共用 schedule_item 一张表、共用 agent 侧同一组
-- schedule_* 工具，只靠 item_type 区分，所以这里只有一行 schedule。
-- 早先预置的 todo 行没有对应工具，是个「有开关、没效果」的死插件，
-- 而且它 default_enabled=1，把唯一有实现的 schedule（默认 0）挤掉了，
-- 导致新用户手里一个服务工具都绑不上。该行已由迁移脚本删除。
--
-- default_enabled 只有日程默认为 1：待办是最高频的「随手记一条」，
-- 而待办已经并入本插件，必须随它一起默认开启，否则新用户开箱没有可用能力。
-- 账单 / 健康涉及更私密的数据，默认关，由用户主动开；
-- 且这两个插件目前还没有工具实现，在工具落地前 default_enabled 必须保持 0。
INSERT INTO `plugin` (`plugin_key`, `name`, `description`, `icon`, `version`, `status`, `default_enabled`, `sort`)
VALUES ('schedule', '日程与待办', '管理日程、待办与提醒', 'schedule', '1.0.0', 1, 1, 10),
       ('bill', '账单', '记录收支与统计', 'bill', '1.0.0', 1, 0, 20),
       ('health', '健康', '记录睡眠、运动与身体数据', 'health', '1.0.0', 1, 0, 30)
ON DUPLICATE KEY UPDATE `plugin_key` = `plugin_key`;
