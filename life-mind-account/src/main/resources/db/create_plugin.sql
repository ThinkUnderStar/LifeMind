-- 插件目录表：life_mind_account.plugin
--
-- 一行 = 一个可插拔的能力插件（todo / schedule / bill / health）。
-- 这张表只回答三个问题：系统里有哪些插件、是否上架、新用户默认要不要开。
-- 用户自己的开关在 user_plugin，两表分开是因为：
--   * 上下架是运维动作（全局，一次生效）
--   * 启停是用户动作（个人，互不影响）
-- 合成一张表会让「下架」和「停用」两个语义混在一起。
--
-- plugin_key 与 agent 侧工具前缀严格一致（todo_create → todo），
-- 并且只增不改：改名等于换插件，前端已存的启用记录、历史服务卡都会对不上。
CREATE TABLE IF NOT EXISTS `plugin` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '插件ID',
  `plugin_key` varchar(50) NOT NULL COMMENT '插件标识：todo/schedule/bill/health，与 agent 工具名前缀一致；只增不改',
  `name` varchar(50) NOT NULL COMMENT '展示名，如 待办',
  `description` varchar(200) DEFAULT NULL COMMENT '一句话说明，插件市场用',
  `icon` varchar(255) DEFAULT NULL COMMENT '图标标识，前端按它取图标',
  `version` varchar(20) NOT NULL DEFAULT '1.0.0' COMMENT '版本号',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '上架状态：0-下架 1-上架；下架等于「拔」，用户配置保留但不生效',
  `default_enabled` tinyint NOT NULL DEFAULT '0' COMMENT '新用户是否默认启用：0-否 1-是；用户没配置过时以此为准',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序，越小越靠前',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plugin_key` (`plugin_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='插件目录表：有哪些可插拔插件、是否上架';
