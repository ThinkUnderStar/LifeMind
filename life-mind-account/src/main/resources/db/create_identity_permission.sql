-- 身份-权限关系表：life_mind_account.identity_permission（结构由库中现存表导出归档）
CREATE TABLE `identity_permission` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `identity_id` bigint NOT NULL COMMENT '身份ID',
  `permission_id` bigint NOT NULL COMMENT '权限ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_identity_permission` (`identity_id`,`permission_id`),
  KEY `idx_identity_permission_permission` (`permission_id`),
  CONSTRAINT `fk_identity_permission_identity` FOREIGN KEY (`identity_id`) REFERENCES `identity` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_identity_permission_permission` FOREIGN KEY (`permission_id`) REFERENCES `permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='身份-权限关系表';