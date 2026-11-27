from typing import Literal, Optional

from pydantic import BaseModel, Field


class ChatDto(BaseModel):
    """
    聊天接口的请求体。

    刻意不含 user_id：身份只认网关注入的请求头（LifeMind-User-Id），
    不接受前端自报，避免越权读别人的会话记忆。
    """

    conversation_id: int = Field(
        ...,
        description="会话ID；同时作为 LangGraph 的 thread_id，一个会话对应一条 checkpoint 线程",
    )
    content: str = Field(
        ...,
        min_length=1,
        description="用户这一轮发的话",
    )
    enable_web_search: int = Field(
        default=0,
        description="本轮是否允许联网搜索：1 开启、0 关闭；默认关闭，模型改不了这个开关",
    )


class ResumeDto(BaseModel):
    """中断恢复（工具确认）的请求体。

    choice 与 approval_gate 里 interrupt 的 options 一一对应，多一个都算非法：
    服务端按白名单校验，避免前端拼出 "approve_all" 之类让工具绕过确认的取值。
    """

    conversation_id: int = Field(
        ...,
        description="会话ID，必须与中断时那条 checkpoint 线程一致；也是归属校验的依据",
    )
    choice: Literal["approve", "reject", "edit"] = Field(
        ...,
        description="approve-按原参数执行 reject-不执行 edit-用 edit_content 替换后执行",
    )
    edit_content: Optional[str] = Field(
        default=None,
        description="choice=edit 时必填，替换后的工具参数内容；其他 choice 忽略此字段",
    )
    enable_web_search: int = Field(
        default=0,
        description="恢复执行时的联网开关，需与本轮首次请求保持一致，否则工具行为会变",
    )


class BacktrackDto(BaseModel):
    """时间回溯：编辑一条历史用户消息，并从它之前的状态重新生成。

    只传 message_id，不传 checkpoint_id：分支点由后端按该消息的 from_checkpoint_id 决定，
    前端拿不到也不需要知道内部快照 ID。
    """

    conversation_id: int = Field(..., description="会话ID")
    message_id: int = Field(..., description="要被改写的那条用户消息ID；它及其之后的消息都会被替换")
    content: str = Field(..., min_length=1, description="改写后的消息内容")


class AttachDto(BaseModel):
    """断线重连：把断线期间攒下的 SSE 帧补发给前端。

    不启动新一轮图执行，也不新增用户消息——纯粹从会话的帧缓冲读。
    after_seq 是前端已收到的最后一帧下标，后端只补发它之后的帧。
    """

    conversation_id: int = Field(..., description="会话ID，与发起时一致；也是归属校验依据")
    after_seq: int = Field(
        default=-1,
        description="已收到的最后一帧下标；-1 表示从第一帧开始补发",
    )


class CreateSessionDto(BaseModel):
    """创建会话的请求体。标题可以不给，由服务端落默认值。"""

    title: Optional[str] = Field(
        default=None,
        max_length=100,
        description="会话标题；不传或为空则使用默认标题",
    )


class UpdateSessionTitleDto(BaseModel):
    """修改会话标题的请求体。"""

    conversation_id: int = Field(..., description="会话ID")
    title: str = Field(
        ...,
        min_length=1,
        max_length=100,
        description="新标题；服务端会去掉首尾空白，纯空白视为非法",
    )


class ArchiveSessionDto(BaseModel):
    """归档 / 取消归档会话的请求体。

    用 archived 布尔而不是两个接口：归档和取消归档是对同一个字段的两种赋值，
    拆成 /archive 与 /unarchive 会让前端多一个 if，后端多一份重复的归属校验。
    """

    conversation_id: int = Field(..., description="会话ID")
    archived: bool = Field(..., description="true-归档（从默认列表移除） false-取消归档")

