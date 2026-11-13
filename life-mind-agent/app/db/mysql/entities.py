from sqlalchemy import (Column, BigInteger, SmallInteger, String, Text,
                        DateTime, func, Index)

from app.db.mysql.session import Base


class ConversationEntity(Base):
    """AI 会话表"""
    __tablename__ = "conversation"

    id = Column(BigInteger, primary_key=True, autoincrement=True, comment="会话ID")
    user_id = Column(BigInteger, nullable=False, index=True, comment="用户ID")
    title = Column(String(255), nullable=False, default="", comment="会话标题")
    status = Column(SmallInteger, nullable=False, default=0, index=True, comment="0-正常 1-已归档")
    create_time = Column(DateTime, nullable=True, server_default=func.now(), comment="创建时间")
    update_time = Column(DateTime, nullable=True, server_default=func.now(), onupdate=func.now(), comment="更新时间")
    deleted = Column(SmallInteger, nullable=False, default=0, comment="0-未删 1-已删")


class ChatMessageEntity(Base):
    """AI 对话消息表"""
    __tablename__ = "chat_message"

    id = Column(BigInteger, primary_key=True, autoincrement=True, comment="消息ID")
    conversation_id = Column(BigInteger, nullable=False, index=True, comment="会话ID")
    role = Column(String(20), nullable=False, comment="消息角色: user-用户 assistant-AI system-系统")
    content = Column(Text, nullable=False, comment="消息内容")
    status = Column(SmallInteger, nullable=False, default=0, index=True, comment="0-正常 1-失败")
    create_time = Column(DateTime, nullable=True, server_default=func.now(), comment="创建时间")
    update_time = Column(DateTime, nullable=True, server_default=func.now(), onupdate=func.now(), comment="更新时间")
    deleted = Column(SmallInteger, nullable=False, default=0, comment="0-未删 1-已删")


class AgentTaskEntity(Base):
    """AI 任务表：一行 = 一个任务（任务即步骤），归属只认 conversation_id；无版本、无计划分组、无变更记录"""
    __tablename__ = "agent_task"
    __table_args__ = (
        Index("idx_conversation", "conversation_id", "id"),
    )

    id = Column(BigInteger, primary_key=True, autoincrement=True, comment="任务ID，主键；展示顺序按 id 升序")
    conversation_id = Column(BigInteger, nullable=False, comment="会话ID，关联 conversation.id；判断同一对话的任务只靠这一列")
    goal = Column(String(1000), nullable=False, comment="任务目标，由 planner 从用户原话转写")
    status = Column(String(20), nullable=False, default="pending",
                    comment="任务状态：pending-开始/待执行 running-执行中 done-已完成 failed-失败或中断 retrying-重试中（原样再跑一次，不改 goal）")
    result = Column(Text, nullable=True, comment="本任务的执行结果（工具产出 / AI 结论）；未执行完为 NULL，改任务或重试重跑时清空")
    create_time = Column(DateTime, nullable=True, server_default=func.now(), comment="创建时间")
    update_time = Column(DateTime, nullable=True, server_default=func.now(), onupdate=func.now(), comment="更新时间")
    deleted = Column(SmallInteger, nullable=False, default=0, comment="0-未删 1-已删")
