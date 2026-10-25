from typing import Optional

from pydantic import BaseModel, Field


class NotificationEvent(BaseModel):
    """
    服务间通知消息体。

    由生产者（账户服务、消息服务、agent 服务等）在事件发生时投递到 MQ，
    消费者（通知服务）接收后组装成 Notification 实体落库并分发。

    字段与 Java 侧 thinkunderstar.lifemind.lifemindaccount.event.NotificationEvent 保持一致，
    且字段名沿用 Java 的驼峰命名，以保证跨语言 MQ 投递时 JSON 字段名一致；
    不包含由消费者生成的字段（id、isRead、readTime、deleted、createTime、updateTime）。
    """

    userId: Optional[int] = Field(default=None, description="接收人用户 ID")
    type: str = Field(
        default="",
        description="通知类型：system-系统通知 like-点赞 comment-评论 reply-回复 follow-关注 feedback-反馈等",
    )
    title: str = Field(default="", description="通知标题")
    content: str = Field(default="", description="通知内容")
    senderId: Optional[int] = Field(default=None, description="发送者用户 ID，系统通知为 null")
    bizType: Optional[str] = Field(default=None, description="关联业务类型，如 post/comment/feedback/task 等，可空")
    bizId: Optional[str] = Field(default=None, description="关联业务 ID，字符串形式以兼容各类业务主键")
    jumpUrl: Optional[str] = Field(default=None, description="点击通知后的跳转地址")
    level: Optional[int] = Field(default=None, description="通知级别：0-普通 1-重要 2-紧急")
    extra: Optional[str] = Field(default=None, description="扩展数据，JSON 格式字符串，存放业务附加参数")
