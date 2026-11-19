"""会话管理接口：创建、改名、删除、列表（含搜索）。

全部是普通 JSON 接口，不是 SSE——它们不触达图，只动 conversation 表。
对话与记忆相关的操作在 chat_router。
"""
from typing import Optional

from fastapi import APIRouter, Body, Depends, Query

from app.api.v1.deps import current_user_id
from app.schemas.web.request import ArchiveSessionDto, CreateSessionDto, UpdateSessionTitleDto
from app.schemas.web.response import ConversationDto, PageData
from app.schemas.web.result import Result
from app.services.v1 import session_service

session_router = APIRouter(prefix="/session", tags=["Session"])


@session_router.post("/create")
async def create_session(
        dto: CreateSessionDto = Body(default=CreateSessionDto(), description="会话标题，可为空"),
        user_id: int = Depends(current_user_id),
) -> Result[ConversationDto]:
    """
    创建会话。

    只写 conversation 一行，不预建图状态；首轮对话跑图时才产生 checkpoint。
    标题不传或为空则落默认值「新对话」。

    Args:
        dto: 请求体，只含可选标题。
        user_id: 网关注入的用户ID（依赖注入，缺失直接 401）。

    Returns:
        Result[ConversationDto]: 新建会话，含 id / title / 时间。
    """
    return await session_service.create_session_service(user_id, dto)


@session_router.put("/title")
async def update_session_title(
        dto: UpdateSessionTitleDto = Body(..., description="会话ID与新标题"),
        user_id: int = Depends(current_user_id),
) -> Result[None]:
    """
    修改会话标题。

    纯空白标题返回 400，不静默改成默认值——改名是用户的明确动作，
    悄悄改掉会让用户以为成功了。

    Args:
        dto: conversation_id + title。
        user_id: 网关注入的用户ID。

    Returns:
        Result[None]: 成功响应。

    Raises:
        BusinessException: 404 会话不存在或不属于该用户；400 标题为空。
    """
    return await session_service.update_session_title_service(user_id, dto)


@session_router.put("/archive")
async def archive_session(
        dto: ArchiveSessionDto = Body(..., description="会话ID与目标归档状态"),
        user_id: int = Depends(current_user_id),
) -> Result[None]:
    """
    归档或取消归档会话。

    归档只是让它从默认列表消失（status=1），数据、消息、记忆全部保留，
    随时可用 `archived=true` 查回来；和删除不是一回事。

    Args:
        dto: conversation_id + archived。
        user_id: 网关注入的用户ID。

    Returns:
        Result[None]: 成功响应。

    Raises:
        BusinessException: 404 会话不存在或不属于该用户。
    """
    return await session_service.archive_session_service(user_id, dto)


@session_router.delete("/{conversation_id}")
async def delete_session(
        conversation_id: int,
        user_id: int = Depends(current_user_id),
) -> Result[None]:
    """
    删除会话（软删，只置 deleted=1）。

    不动 checkpoint / Milvus / 消息表；要彻底清记忆请调用
    `DELETE /chat/memory/{conversation_id}`。两者分开是为了「删错了还能恢复」
    和「不可恢复的清除」不混在一个动作里。

    Args:
        conversation_id: 要删除的会话ID。
        user_id: 网关注入的用户ID。

    Returns:
        Result[None]: 成功响应。

    Raises:
        BusinessException: 404 会话不存在或无权访问。
    """
    return await session_service.delete_session_service(user_id, conversation_id)


@session_router.get("/list")
async def list_sessions(
        keyword: Optional[str] = Query(default=None, max_length=100, description="标题关键词，为空即默认列表"),
        page: int = Query(default=1, ge=1, description="页码，从 1 开始"),
        size: int = Query(default=20, ge=1, le=100, description="每页条数，上限 100"),
        archived: bool = Query(default=False, description="false-正常列表 true-归档列表"),
        user_id: int = Depends(current_user_id),
) -> Result[PageData[ConversationDto]]:
    """
    分页获取会话列表，支持按标题搜索。

    「默认列表」与「搜索」是同一个实现：keyword 为空即默认列表。
    按 update_time 倒序（最近聊过的在前），只返回未删除的会话。
    archived 切换正常/归档两个视图，默认只看正常的。

    Args:
        keyword: 标题模糊匹配关键词，可选。
        page: 页码，从 1 开始。
        size: 每页条数，上限 100。
        archived: false 查正常会话，true 查已归档会话。
        user_id: 网关注入的用户ID。

    Returns:
        Result[PageData[ConversationDto]]: records / total / size / current。
    """
    return await session_service.list_sessions_service(
        user_id=user_id,
        keyword=keyword,
        page=page,
        size=size,
        archived=archived,
    )
