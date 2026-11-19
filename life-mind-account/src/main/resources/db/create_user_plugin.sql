-- 用户插件启用表：life_mind_account.user_plugin
--
-- 一行 = 一个用户对一个插件的一种状态（enabled 0/1），unique(user_id, plugin_id) 保证只有一行。
--
-- 为什么保留 enabled 列，而不是「有行=启用、无行=未启用」：
--   default_enabled=1 的插件，用户主动关掉后没有行可删，无法表达「我关了它」；
--   而且「从未配置」和「配置成关闭」是两种状态，前者要跟随默认值变化，后者不该被默认值覆盖。
--   保留 enabled 后，update_time 天然就是启用/停用时间，审计也够用。
--
-- 下架（plugin.status=0）不删本表的行：用户配置要留着，
-- 插件重新上架后用户的选择应当恢复，而不是被悄悄重置成默认值。
CREATE TABLE IF NOT EXISTS `user_plugin` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `plugin_id` bigint NOT NULL COMMENT '插件ID',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '0-已停用 1-已启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次配置时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后变更时间，停用时即为停用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_plugin` (`user_id`,`plugin_id`),
  KEY `idx_user_plugin_user` (`user_id`),
  CONSTRAINT `fk_user_plugin_plugin` FOREIGN KEY (`plugin_id`) REFERENCES `plugin` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户插件启用表：一行一用户一插件';
