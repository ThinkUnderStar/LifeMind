from sqlalchemy import (Column, BigInteger, Integer, SmallInteger, String, Text,
                        DateTime, func, UniqueConstraint, Index)

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
    """AI 任务表：一行 = 前端一张任务卡，plan 变更时原地 UPDATE 本行"""
    __tablename__ = "agent_task"
    __table_args__ = (
        UniqueConstraint("plan_id", name="uk_plan_id"),
        Index("idx_task_conversation", "conversation_id", "create_time"),
    )

    id = Column(BigInteger, primary_key=True, autoincrement=True, comment="任务ID")
    conversation_id = Column(BigInteger, nullable=False, comment="会话ID，关联 conversation.id")
    plan_id = Column(String(64), nullable=False, comment="计划ID，同一张任务卡原地更新，不随重规划变化")
    goal = Column(String(1000), nullable=False, comment="任务目标，由 planner 从用户原话转写")
    status = Column(String(20), nullable=False, default="running",
                    comment="任务状态：running-执行中 done-已完成 failed-失败 interrupted-确认被拒/中断")
    version = Column(Integer, nullable=False, default=1,
                     comment="计划版本号，每次重规划 +1，前端按 version 幂等去重")
    reason = Column(String(500), nullable=True, comment="最近一次计划变更原因")
    create_time = Column(DateTime, nullable=True, server_default=func.now(), comment="创建时间")
    update_time = Column(DateTime, nullable=True, server_default=func.now(), onupdate=func.now(), comment="更新时间")
    deleted = Column(SmallInteger, nullable=False, default=0, comment="0-未删 1-已删")


class AgentTaskStepEntity(Base):
    """AI 任务步骤表：一行 = 一个步骤，状态变更即落库，展示顺序按 id 升序"""
    __tablename__ = "agent_task_step"
    __table_args__ = (
        UniqueConstraint("task_id", "step_id", name="uk_task_step"),
        Index("idx_step_task_status", "task_id", "status"),
    )

    id = Column(BigInteger, primary_key=True, autoincrement=True, comment="步骤行ID")
    task_id = Column(BigInteger, nullable=False, comment="所属任务ID，关联 agent_task.id")
    step_id = Column(String(64), nullable=False, comment="计划内步骤编号，作废后追加新编号，不复用")
    goal = Column(String(1000), nullable=False, comment="本步目标")
    status = Column(String(20), nullable=False, default="pending",
                    comment="步骤状态：pending-待执行 running-执行中 done-已完成 skipped-已跳过 failed-失败")
    outcome = Column(String(500), nullable=True,
                     comment="状态说明 / 失败或跳过原因；不是执行结果，不存工具返回内容")
    added_in = Column(Integer, nullable=False, default=1, comment="加入时的计划版本号，前端据此标记新增步骤")
    changed_in = Column(Integer, nullable=True, comment="最后一次改动所在的计划版本号，NULL 表示从未改动")
    create_time = Column(DateTime, nullable=True, server_default=func.now(), comment="创建时间")
    update_time = Column(DateTime, nullable=True, server_default=func.now(), onupdate=func.now(), comment="更新时间")
    deleted = Column(SmallInteger, nullable=False, default=0, comment="0-未删 1-已删")
