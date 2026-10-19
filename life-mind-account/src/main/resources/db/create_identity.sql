-- 身份表：life_mind_account.identity（结构由库中现存表导出归档）
CREATE TABLE `identity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '身份ID',
  `code` varchar(50) NOT NULL COMMENT '身份标识，如 ROLE_ADMIN',
  `name` varchar(50) NOT NULL COMMENT '身份名称',
  `description` varchar(255) DEFAULT NULL COMMENT '描述',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序，越小越靠前',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_identity_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='身份表';