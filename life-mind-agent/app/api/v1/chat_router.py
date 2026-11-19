"""聊天与记忆操作接口。

共 7 个接口，分四类：
- 流式（SSE）：send / resume / backtrack，都会触达图，所以返回 text/event-stream；
- 只读查询：messages / tasks / pending，读 MySQL 或读一次图状态快照，不推进图；
- 存储维护：删除会话记忆，只动存储不动图。

归属校验一律放在构造 StreamingResponse 之前：流已经开始后再抛异常，
前端收到的是半截 SSE 帧而不是 404，无法按状态码处理。
"""
from typing import List, Optional

from fastapi import APIRouter, Body, Depends, Query, Request
from starlette.responses import StreamingResponse

from app.agent.graph import get_graph
from app.api.v1.deps import current_plugins, current_user_id
from app.schemas.web.request import BacktrackDto, ChatDto, ResumeDto
from app.schemas.web.response import MessageListDto, PendingInterruptDto, TaskDto
from app.schemas.web.result import Result
from app.services.v1 import history_service
from app.services.v1.chat_service import (
    assert_has_pending_interrupt,
    backtrack_chat_service,
    chat_with_agent_service,
    clear_conversation_memory_service,
    get_pending_interrupt_service,
    resume_chat_service,
)
from app.services.v1.session_service import require_owned_conversation

chat_router = APIRouter(prefix="/chat", tags=["Agent"])


def _sse_response(stream) -> StreamingResponse:
    """
    统一的 SSE 响应包装。

    头部三件套缺一不可：no-cache 防浏览器缓存旧帧，keep-alive 保长连接，
    X-Accel-Buffering 关掉 Nginx 缓冲（否则前端会等整段生成完才一次性收到）。

    :param stream: 异步生成器，逐帧产出 SSE 字符串
    :return: StreamingResponse
    """
    return StreamingResponse(
        stream,
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@chat_router.post("/send")
async def chat_with_agent(
        request: Request,
        chat_dto: ChatDto = Body(..., description="用户这一轮的话、所属会话与联网开关"),
        user_id: int = Depends(current_user_id),
        enabled_plugins: str = Depends(current_plugins),
) -> StreamingResponse:
    """
    与 Agent 流式对话（SSE 实时推送）。

    路由只做四件事：校验身份、校验会话归属、取编译好的图、把 service 的异步生成器包成 SSE。
    真正的图调用与事件筛选在 `app.services.v1.chat_service`。

    事件类型（均为 `data: {"type":..., "data":...}` 的 JSON 帧）：
    - `text`      回复文本片段，前端直接追加
    - `interrupt` 需要用户确认（如保存用户级记忆），前端弹窗后调用 `/chat/resume`
    - `done`      本轮结束
    - `error`     执行失败

    Args:
        request: 用于取 `app.state` 上惰性编译的图（依赖 lifespan 已连好 checkpointer / store）。
        chat_dto: 请求体，含 conversation_id、content、enable_web_search。
        user_id: 网关校验 JWT 后注入的用户ID；缺失 401、非法 400。

    Returns:
        StreamingResponse: `text/event-stream` 流。

    Raises:
        BusinessException: 身份缺失/非法；会话不存在或不属于当前用户（404）。
    """
    # 会话必须已存在且属于自己：不校验就等于允许往别人的 thread 里写消息
    await require_owned_conversation(user_id, chat_dto.conversation_id)

    graph = get_graph(request.app)
    return _sse_response(
        chat_with_agent_service(graph, user_id, chat_dto, enabled_plugins=enabled_plugins))


@chat_router.post("/resume")
async def resume_chat(
        request: Request,
        resume_dto: ResumeDto = Body(..., description="中断确认结果：会话ID + 选择 + 可选编辑内容"),
        user_id: int = Depends(current_user_id),
        enabled_plugins: str = Depends(current_plugins),
) -> StreamingResponse:
    """
    工具调用中断恢复（SSE 实时推送）。

    当 `approval_gate` 触发 interrupt 时，前端弹窗让用户选择，然后调用本接口把选择回传，
    图从暂停处继续执行，后续事件类型与 `/chat/send` 完全一致（text / interrupt / done）。

    可能再次中断：一次模型返回多个工具调用时，确认是一个个弹的，
    所以恢复后仍可能收到新的 `interrupt`，前端要按同样方式处理。

    Args:
        request: 用于取编译好的图。
        resume_dto: conversation_id、choice（approve/reject/edit）、edit_content、联网开关。
        user_id: 网关注入的用户ID。

    Returns:
        StreamingResponse: `text/event-stream` 流。

    Raises:
        BusinessException: 身份缺失/非法；会话不属于当前用户（404）；
                           选择编辑但没给内容（400）。
    """
    await require_owned_conversation(user_id, resume_dto.conversation_id)

    graph = get_graph(request.app)

    # 没有挂起的中断时直接 400：重复提交同一个确认是这个接口最常见的误用
    await assert_has_pending_interrupt(graph, resume_dto.conversation_id)

    return _sse_response(
        resume_chat_service(graph, user_id, resume_dto, enabled_plugins=enabled_plugins))


@chat_router.post("/backtrack")
async def backtrack_chat(
        request: Request,
        backtrack_dto: BacktrackDto = Body(..., description="会话ID + 被改写的用户消息ID + 新内容"),
        user_id: int = Depends(current_user_id),
        enabled_plugins: str = Depends(current_plugins),
) -> StreamingResponse:
    """
    时间回溯：改写一条历史用户消息并从那里重新生成（SSE 实时推送）。

    会先清掉该消息及其之后的消息、任务与向量切片，再用该消息的父快照分叉重跑。
    属于不可逆操作，前端应先做二次确认。

    与 `/chat/send` 的差别：send 接着最新状态往下聊；本接口回到历史某一点，
    之后的旧回复不会成为新回复的上下文。

    Args:
        request: 用于取编译好的图。
        backtrack_dto: conversation_id、message_id（要改写的用户消息）、content（新内容）。
        user_id: 网关注入的用户ID。

    Returns:
        StreamingResponse: `text/event-stream` 流。

    Raises:
        BusinessException: 身份缺失/非法；会话不属于当前用户（404）；
                           消息不存在（404）或不是用户消息（400）。
    """
    await require_owned_conversation(user_id, backtrack_dto.conversation_id)

    graph = get_graph(request.app)
    return _sse_response(
        backtrack_chat_service(graph, user_id, backtrack_dto, enabled_plugins=enabled_plugins))


@chat_router.get("/messages/{conversation_id}")
async def list_messages(
        conversation_id: int,
        before_id: Optional[int] = Query(
            default=None, ge=1,
            description="只取该消息ID之前（更早）的消息；拉第一页时不传",
        ),
        size: int = Query(default=30, ge=1, le=100, description="每页条数，上限 100"),
        user_id: int = Depends(current_user_id),
) -> Result[MessageListDto]:
    """
    分页读取会话的历史消息（按时间正序返回）。

    前端打开会话时用它恢复聊天记录。用游标（before_id）而非页码：
    聊天记录会持续新增，页码分页在翻历史时容易错位。
    上一页返回的 `next_before_id` 直接作为本次的 `before_id` 传入。

    Args:
        conversation_id: 会话ID。
        before_id: 游标，只取更早的消息；首页不传。
        size: 每页条数，默认 30、上限 100。
        user_id: 网关注入的用户ID。

    Returns:
        Result[MessageListDto]: records（正序）+ has_more + next_before_id。

    Raises:
        BusinessException: 会话不存在或无权访问（404）。
    """
    return await history_service.list_messages_service(
        user_id=user_id,
        conversation_id=conversation_id,
        before_id=before_id,
        size=size,
    )


@chat_router.get("/tasks/{conversation_id}")
async def list_tasks(
        conversation_id: int,
        user_id: int = Depends(current_user_id),
) -> Result[List[TaskDto]]:
    """
    读取会话下的任务清单（按 id 升序，即执行顺序）。

    用途是任务进度看板：前端拿到 goal / status / result 就能画出每一步在做什么、
    是否完成，以及显示 tool_calls 之前的那段空白（任务分支要跑很久才有回复文本）。
    不分页：一轮任务通常 1–10 条。

    Args:
        conversation_id: 会话ID。
        user_id: 网关注入的用户ID。

    Returns:
        Result[List[TaskDto]]: 任务清单；没有任务时为空列表。

    Raises:
        BusinessException: 会话不存在或无权访问（404）。
    """
    return await history_service.list_tasks_service(user_id, conversation_id)


@chat_router.get("/pending/{conversation_id}")
async def get_pending_interrupt(
        request: Request,
        conversation_id: int,
        user_id: int = Depends(current_user_id),
) -> Result[PendingInterruptDto]:
    """
    查询会话当前是否有挂起的人工确认。

    刷新页面、断线重连后，前端不知道流断在哪里，用这个接口决定要不要重新弹确认框。
    没有中断是正常状态（返回 has_pending=false），不报错——
    与 `/chat/resume` 的前置校验刻意不同：那个是拦截非法恢复，必须 400。

    Args:
        request: 用于取编译好的图。
        conversation_id: 会话ID。
        user_id: 网关注入的用户ID。

    Returns:
        Result[PendingInterruptDto]: has_pending + 中断载荷（与 SSE interrupt 帧一致）。

    Raises:
        BusinessException: 会话不存在或无权访问（404）。
    """
    await require_owned_conversation(user_id, conversation_id)

    graph = get_graph(request.app)
    return await get_pending_interrupt_service(graph, conversation_id)


@chat_router.delete("/memory/{conversation_id}")
async def clear_conversation_memory(
        conversation_id: int,
        user_id: int = Depends(current_user_id),
) -> Result[None]:
    """
    删除指定会话的全部记忆（**不可恢复**）。

    清除范围：
    - PostgreSQL：该 thread 的所有 checkpoint（图状态）；
    - Milvus：该会话专属向量集合（会话回忆）；
    - MySQL：chat_message 与 agent_task 软删。

    不动 conversation 行本身——删会话走 `DELETE /session/{id}`。
    用户级长期记忆（按 user_id 隔离的 store）不受影响。

    Args:
        conversation_id: 目标会话ID（路径参数）。
        user_id: 网关注入的用户ID。

    Returns:
        Result[None]: 成功响应。

    Raises:
        BusinessException: 会话不存在或无权访问（404）。
    """
    return await clear_conversation_memory_service(user_id, conversation_id)
