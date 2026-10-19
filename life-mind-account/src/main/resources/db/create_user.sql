-- 用户表：life_mind_account.user（结构由库中现存表导出归档）
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` varchar(50) NOT NULL COMMENT '登录名',
  `username_change_time` datetime DEFAULT NULL COMMENT '上次修改账户名的时间，NULL 表示从未改过；一年内只能改一次',
  `password` varchar(100) NOT NULL COMMENT '密码（BCrypt 加密后的密文）',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像地址',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0-禁用 1-正常',
  `token_version` int NOT NULL DEFAULT '0' COMMENT '令牌版本号：改密后+1，使此前签发的JWT失效',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`),
  UNIQUE KEY `uk_user_email` (`email`),
  UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';