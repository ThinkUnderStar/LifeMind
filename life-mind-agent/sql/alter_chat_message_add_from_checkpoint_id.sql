-- chat_message 增加「分支源快照」列，供时间回溯（编辑历史消息后重新生成）使用。
--
-- 语义：记录这一轮 user 消息开始前所在的 LangGraph checkpoint_id。
--   回溯时把它作为 configurable.checkpoint_id 交给图，图会以该快照为父节点分叉，
--   而不是清空整条线程从头重跑——这样历史上下文和已产生的向量都能保留。
-- 取值：仅 role=user 的行有值；assistant / system 为 NULL；会话首轮没有父快照，也是 NULL。
-- 幂等：加 IF NOT EXISTS 逻辑用 information_schema 判断，MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS，
--       重复执行会报 1060 列已存在，属预期，可忽略。

ALTER TABLE `chat_message`
    ADD COLUMN `from_checkpoint_id` VARCHAR(255) NULL
        COMMENT '分支源快照ID：本条user消息所在轮的父checkpoint，回溯重生成时从它分叉；仅user消息有值，首轮为NULL'
        AFTER `role`;
