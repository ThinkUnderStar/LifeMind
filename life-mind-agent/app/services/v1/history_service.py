"""会话历史的只读查询：历史消息与任务清单。

为什么单独一个模块而不是塞进 chat_service：
chat_service 负责「跑图 + 推 SSE」，失败面在模型和图上；
这里只做数据库读取，不碰图、不写库。两者混在一起后，
读历史接口的一个 SQL 错误会把整条流式链路的日志搅乱。

归属校验统一走 session_service.require_owned_conversation，
不在本模块重复写 where user_id 条件——那样漏一处就是越权读别人的聊天记录。
"""
import logging
from typing import List, Optional

from sqlalchemy import select

from app.db.mysql.entities import AgentTaskEntity, ChatMessageEntity
from app.db.mysql.session import session_scope
from app.schemas.web.response import MessageDto, MessageListDto, TaskDto
from app.schemas.web.result import Result
from app.services.v1.session_service import require_owned_conversation

logger = logging.getLogger(__name__)

# 一页默认 30 条、上限 100 条。
# 上限存在的原因是防止前端传 size=100000 把整段会话一次拉走：
# 消息正文是 TEXT，几百条就是几 MB，会把 agent 进程的内存和网关带宽顶满
DEFAULT_MESSAGE_PAGE_SIZE = 30
MAX_MESSAGE_PAGE_SIZE = 100


async def list_messages_service(
        user_id: int,
        conversation_id: int,
        before_id: Optional[int] = None,
        size: int = DEFAULT_MESSAGE_PAGE_SIZE,
) -> Result[MessageListDto]:
    """
    按游标分页读取历史消息，返回按时间正序的一页。

    查询方向是「id 倒序取 N 条」，返回前再翻正：
    前端打开会话时最关心的是最新几条，倒序取才能利用主键索引且只读一页；
    而渲染顺序又必须是先老后新，所以翻一次。

    :param user_id: 网关注入的用户ID，用于归属校验
    :param conversation_id: 会话ID
    :param before_id: 只取该消息ID之前的（更早的）消息；首页传 None
    :param size: 每页条数，超过上限按上限处理
    :return: 消息页 + 是否还有更早的消息
    """
    # 会话不存在或不属于自己，直接 404，不进入消息查询
    await require_owned_conversation(user_id, conversation_id)

    size = min(max(size, 1), MAX_MESSAGE_PAGE_SIZE)

    conditions = [
        ChatMessageEntity.conversation_id == conversation_id,
        ChatMessageEntity.deleted == 0,
    ]
    if before_id is not None:
        conditions.append(ChatMessageEntity.id < before_id)

    async with session_scope() as db:
        # 多取一条用来判断 has_more，比再查一次 count 便宜
        rows = (await db.execute(
            select(ChatMessageEntity)
            .where(*conditions)
            .order_by(ChatMessageEntity.id.desc())
            .limit(size + 1)
        )).scalars().all()

        has_more = len(rows) > size
        page = rows[:size]
        # 倒序取出来的直接反转，得到时间正序；同时在会话内完成 DTO 转换，
        # 避免出了 with 之后再访问 ORM 属性触发隐式加载
        records = [MessageDto.model_validate(row) for row in reversed(page)]

    next_before_id = records[0].id if (has_more and records) else None

    return Result.success(data=MessageListDto(
        records=records,
        has_more=has_more,
        next_before_id=next_before_id,
    ))


async def list_tasks_service(user_id: int, conversation_id: int) -> Result[List[TaskDto]]:
    """
    读取一条会话下的全部任务，按 id 升序（即执行顺序）。

    不分页：任务数由 planner 生成，一轮通常 1–10 条，
    分页会让前端多一层状态而收益为零。已软删的不返回。

    :param user_id: 网关注入的用户ID，用于归属校验
    :param conversation_id: 会话ID
    :return: 任务清单
    """
    await require_owned_conversation(user_id, conversation_id)

    async with session_scope() as db:
        rows = (await db.execute(
            select(AgentTaskEntity)
            .where(
                AgentTaskEntity.conversation_id == conversation_id,
                AgentTaskEntity.deleted == 0,
            )
            .order_by(AgentTaskEntity.id.asc())
        )).scalars().all()

        data = [TaskDto.model_validate(row) for row in rows]

    return Result.success(data=data)
