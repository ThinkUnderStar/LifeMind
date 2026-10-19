-- 权限表：life_mind_account.permission（结构由库中现存表导出归档）
CREATE TABLE `permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '权限ID',
  `code` varchar(100) NOT NULL COMMENT '权限标识，如 account:user:list',
  `name` varchar(50) NOT NULL COMMENT '权限名称',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型：1-菜单 2-按钮 3-接口',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父权限ID，0 表示顶级',
  `path` varchar(200) DEFAULT NULL COMMENT '前端路由 / 资源路径',
  `method` varchar(10) DEFAULT NULL COMMENT 'HTTP 方法，type=3 时使用',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序，越小越靠前',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_permission_code` (`code`),
  KEY `idx_permission_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限表';