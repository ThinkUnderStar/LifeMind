-- 用户-身份关系表：life_mind_account.user_identity（结构由库中现存表导出归档）
CREATE TABLE `user_identity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `identity_id` bigint NOT NULL COMMENT '身份ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_identity` (`user_id`,`identity_id`),
  KEY `idx_user_identity_identity` (`identity_id`),
  CONSTRAINT `fk_user_identity_identity` FOREIGN KEY (`identity_id`) REFERENCES `identity` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_identity_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户-身份关系表';