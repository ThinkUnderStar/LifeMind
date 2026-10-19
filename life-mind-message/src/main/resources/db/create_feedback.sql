-- 用户反馈表：life_mind_message.feedback（结构由库中现存表导出归档）
CREATE TABLE `feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '反馈ID',
  `user_id` bigint NOT NULL COMMENT '反馈用户ID',
  `type` tinyint NOT NULL DEFAULT '4' COMMENT '反馈类型：1-功能建议 2-Bug反馈 3-体验问题 4-其他',
  `content` varchar(1000) NOT NULL COMMENT '反馈内容',
  `images` varchar(2000) DEFAULT NULL COMMENT '反馈图片地址列表，JSON数组格式字符串',
  `contact` varchar(100) DEFAULT NULL COMMENT '联系方式（邮箱/手机号等，便于官方回复，可空）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '处理状态：0-待处理 1-处理中 2-已回复 3-已关闭',
  `reply` varchar(1000) DEFAULT NULL COMMENT '管理员回复内容',
  `reply_user_id` bigint DEFAULT NULL COMMENT '回复人（管理员）用户ID',
  `reply_time` datetime DEFAULT NULL COMMENT '回复时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_feedback_user` (`user_id`),
  KEY `idx_feedback_status` (`status`),
  KEY `idx_feedback_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户反馈表';