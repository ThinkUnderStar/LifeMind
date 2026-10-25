from sqlalchemy import Column, BigInteger, SmallInteger, String, Text, DateTime, func

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
