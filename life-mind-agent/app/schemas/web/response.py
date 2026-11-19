"""Web 层的出参结构。

只放「接口要返回给前端的东西」，不放业务对象本身：
- ConversationDto 是 conversation 表的展示子集，不暴露 user_id / deleted 这类内部字段；
- PageData 是分页的统一外壳，字段名与 Java 侧 Page 对齐（records / total / size / current），
  前端从 Java 接口切到 Python 接口时不用改解析逻辑。
"""
from datetime import datetime
from typing import Any, Generic, List, Optional, TypeVar

from pydantic import BaseModel, ConfigDict, Field

T = TypeVar("T")


class ConversationDto(BaseModel):
    """会话的展示对象。

    from_attributes 让 ORM 实体可以直接 model_validate 转换，
    不用在 service 里手写一遍字段搬运（少一处漏字段的机会）。
    """

    model_config = ConfigDict(from_attributes=True)

    id: int = Field(description="会话ID，同时是 LangGraph 的 thread_id")
    title: str = Field(description="会话标题")
    status: int = Field(description="0-正常 1-已归档")
    create_time: Optional[datetime] = Field(default=None, description="创建时间")
    update_time: Optional[datetime] = Field(default=None, description="最后更新时间")


class PageData(BaseModel, Generic[T]):
    """分页返回外壳，字段名对齐 Java 侧 Page。"""

    records: List[T] = Field(default_factory=list, description="当前页数据")
    total: int = Field(default=0, description="符合条件的总条数")
    size: int = Field(default=20, description="每页条数")
    current: int = Field(default=1, description="当前页码，从 1 开始")


class MessageDto(BaseModel):
    """单条对话消息的展示对象。

    只暴露前端渲染需要的字段：`user_id` 属于内部归属信息，不需要也不该给出去。
    task_bind_id 保留，前端据此把一条 AI 回复和它那一轮的任务挂在一起。
    """

    model_config = ConfigDict(from_attributes=True)

    id: int = Field(description="消息ID；回溯接口要用的就是 user 消息的这个 id")
    role: str = Field(description="消息角色：user / assistant / system")
    content: str = Field(description="消息正文")
    status: int = Field(
        description="消息状态，预留字段：当前写入路径恒为 0，尚无「生成失败」的落库逻辑",
    )
    task_bind_id: Optional[str] = Field(
        default=None, description="任务绑定ID：仅 assistant 消息有值，与 agent_task 同值",
    )
    create_time: Optional[datetime] = Field(default=None, description="创建时间")


class MessageListDto(BaseModel):
    """历史消息的一页，按时间正序返回。

    用游标（before_id）而不是页码：聊天记录会持续新增，
    页码分页在翻历史时容易因为新消息插入而错位、重复或漏条。
    """

    records: List[MessageDto] = Field(default_factory=list, description="按时间正序的消息")
    has_more: bool = Field(default=False, description="是否还有更早的消息")
    next_before_id: Optional[int] = Field(
        default=None, description="下一页要传的 before_id；has_more=false 时为 None",
    )


class TaskDto(BaseModel):
    """任务清单里的一条任务。"""

    model_config = ConfigDict(from_attributes=True)

    id: int = Field(description="任务ID，也是 update_tasks 工具里改任务用的 id")
    goal: str = Field(description="任务目标")
    status: str = Field(description="pending-待执行 running-执行中 done-本条已结束")
    result: Optional[str] = Field(default=None, description="执行结果；未执行完为 None")
    create_time: Optional[datetime] = Field(default=None, description="创建时间")
    update_time: Optional[datetime] = Field(default=None, description="最后更新时间")


class PendingInterruptDto(BaseModel):
    """线程是否停在人工确认上。

    前端刷新页面或断线重连后靠它决定要不要重新弹确认框；
    没有挂起时 interrupt 为 None，前端不必区分「没跑过」和「已经确认完」。
    """

    has_pending: bool = Field(default=False, description="是否存在挂起的中断")
    interrupt: Optional[Any] = Field(
        default=None, description="中断载荷，与 SSE 的 interrupt 帧 data 完全一致",
    )
