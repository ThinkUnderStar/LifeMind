"""会话（conversation）相关的业务实现。

职责边界：只做 conversation 表本身的增删改查。
对话消息、任务、向量记忆的清理不在这里——那是 chat_service 的「删除会话记忆」，
两者刻意分开：删会话是软删一行（可留档），删记忆是不可恢复的清除（动 checkpoint / Milvus）。

所有方法都以 user_id 作为查询条件的一部分，而不是查出来再比对：
少一次判断，也避免「先查后判」中间被改掉归属的情况。
"""
import logging
from typing import Optional

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.mysql.entities import ConversationEntity
from app.db.mysql.session import session_scope
from app.exception.exceptions import BusinessException
from app.schemas.web.request import ArchiveSessionDto, CreateSessionDto, UpdateSessionTitleDto
from app.schemas.web.response import ConversationDto, PageData
from app.schemas.web.result import Result

logger = logging.getLogger(__name__)

# 新建会话的默认标题。前端也可以不传标题，统一落这个值，避免列表里出现空标题
DEFAULT_SESSION_TITLE = "新对话"

# 与 conversation.title 的列定义保持一致（VARCHAR(255)），但业务上限收窄到 100：
# 再长在列表里也显示不全，且极端长标题会把前端布局撑坏
TITLE_MAX_LEN = 100

DEFAULT_PAGE_SIZE = 20
MAX_PAGE_SIZE = 100


def _normalize_title(raw: Optional[str]) -> str:
    """标题规范化：去首尾空白、空值落默认、超长截断。"""
    title = (raw or "").strip()
    if not title:
        return DEFAULT_SESSION_TITLE
    return title[:TITLE_MAX_LEN]


def _escape_like(keyword: str) -> str:
    """
    转义 LIKE 通配符。

    用户搜「100%」时要按字面匹配，不能把 % 当成「任意字符」，
    否则搜一个 % 就会把整个列表都匹配出来。
    """
    return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


async def _get_owned(db: AsyncSession, user_id: int, conversation_id: int) -> ConversationEntity:
    """
    取一条属于该用户、且未删除的会话；取不到一律按「不存在或无权访问」处理。

    不区分「不存在」和「是别人的」：区分开等于告诉调用方某个 ID 真实存在，
    属于不必要的信息泄露。
    """
    query = select(ConversationEntity).where(
        ConversationEntity.id == conversation_id,
        ConversationEntity.user_id == user_id,
        ConversationEntity.deleted == 0,
    )
    entity = (await db.execute(query)).scalar_one_or_none()
    if entity is None:
        raise BusinessException(message="会话不存在或无权访问", code=404)
    return entity


async def require_owned_conversation(user_id: int, conversation_id: int) -> ConversationEntity:
    """
    供 chat 侧复用的归属校验入口。

    chat / resume / backtrack / 删除记忆都要先过这一关，统一放在这里，
    避免每个接口各写一份查询条件、漏掉某个条件就变成越权。
    """
    async with session_scope() as db:
        return await _get_owned(db, user_id, conversation_id)


async def create_session_service(user_id: int, dto: CreateSessionDto) -> Result[ConversationDto]:
    """
    创建一条新会话。

    不预建任何图状态：checkpoint 在首轮对话真正跑图时才写入，
    这里只落一行 conversation，保证列表里立刻能看到。

    :param user_id: 网关注入的用户ID
    :param dto: 创建参数，标题可为空
    :return: 新建会话的展示对象
    """
    async with session_scope() as db:
        entity = ConversationEntity(user_id=user_id, title=_normalize_title(dto.title))
        db.add(entity)
        await db.commit()
        # refresh 拿回自增 id 与库时间，会话内直接转换，避免出了 with 之后再触发属性加载
        await db.refresh(entity)
        data = ConversationDto.model_validate(entity)

    logger.info("会话创建成功：user_id=%s conversation_id=%s title=%s", user_id, data.id, data.title)
    return Result.success(data=data, message="会话创建成功")


async def update_session_title_service(user_id: int, dto: UpdateSessionTitleDto) -> Result[None]:
    """
    修改会话标题。

    纯空白标题在这里拒绝，而不是静默落成「新对话」：改名是用户的明确动作，
    把非法输入悄悄改成默认值，用户会以为改成功了。

    :param user_id: 网关注入的用户ID
    :param dto: 会话ID + 新标题
    :return: 无数据的成功响应
    """
    title = dto.title.strip()
    if not title:
        raise BusinessException(message="会话标题不能为空", code=400)
    title = title[:TITLE_MAX_LEN]

    async with session_scope() as db:
        entity = await _get_owned(db, user_id, dto.conversation_id)
        entity.title = title
        await db.commit()

    logger.info("会话标题已修改：user_id=%s conversation_id=%s", user_id, dto.conversation_id)
    return Result.success(message="会话标题已修改")


async def delete_session_service(user_id: int, conversation_id: int) -> Result[None]:
    """
    删除会话（软删，只置 deleted=1）。

    与「删除会话记忆」分开：这里不动 checkpoint / Milvus / 消息表，
    删错了还能把 deleted 改回来；真要清干净走 chat 的删除记忆接口。

    :param user_id: 网关注入的用户ID
    :param conversation_id: 要删除的会话ID
    :return: 无数据的成功响应
    """
    async with session_scope() as db:
        entity = await _get_owned(db, user_id, conversation_id)
        entity.deleted = 1
        await db.commit()

    logger.info("会话已删除(软删)：user_id=%s conversation_id=%s", user_id, conversation_id)
    return Result.success(message="会话已删除")


async def archive_session_service(user_id: int, dto: ArchiveSessionDto) -> Result[None]:
    """
    归档或取消归档会话（只置 status，不动 deleted）。

    归档与软删的区别：归档的会话仍在库里、仍能按 archived=true 查出来，
    只是不出现在默认列表；软删是 deleted=1，任何列表都不再返回。
    这是两种不同意图，不能合并成一个字段。

    :param user_id: 网关注入的用户ID
    :param dto: 会话ID + 目标归档状态
    :return: 无数据的成功响应
    """
    async with session_scope() as db:
        entity = await _get_owned(db, user_id, dto.conversation_id)
        entity.status = 1 if dto.archived else 0
        await db.commit()

    logger.info(
        "会话归档状态已更新：user_id=%s conversation_id=%s archived=%s",
        user_id, dto.conversation_id, dto.archived,
    )
    return Result.success(message="会话已归档" if dto.archived else "会话已取消归档")


async def list_sessions_service(
        user_id: int,
        keyword: Optional[str] = None,
        page: int = 1,
        size: int = DEFAULT_PAGE_SIZE,
        archived: bool = False,
) -> Result[PageData[ConversationDto]]:
    """
    分页查询会话列表，可选按标题关键词搜索。

    「默认列表」和「搜索列表」合并成一个实现：keyword 为空就是默认列表，
    否则在同样的条件上叠加标题模糊匹配。两条独立的接口会让前端维护两套分页状态。

    只返回指定归档状态下的会话：默认查 status=0（正常），archived=True 时查 status=1。
    排序按 update_time 倒序，最近聊过的排最前，id 作为稳定的次级排序键。

    :param user_id: 网关注入的用户ID
    :param keyword: 标题关键词，可为空
    :param page: 页码，从 1 开始；小于 1 按 1 处理
    :param size: 每页条数；超过上限按上限处理，避免一次拉全表
    :param archived: false-正常列表 true-归档列表
    :return: 分页结果
    """
    page = max(page, 1)
    size = min(max(size, 1), MAX_PAGE_SIZE)

    conditions = [
        ConversationEntity.user_id == user_id,
        ConversationEntity.deleted == 0,
        ConversationEntity.status == (1 if archived else 0),
    ]

    normalized = (keyword or "").strip()
    if normalized:
        conditions.append(
            ConversationEntity.title.like(f"%{_escape_like(normalized)}%", escape="\\")
        )

    async with session_scope() as db:
        total = (await db.execute(
            select(func.count()).select_from(ConversationEntity).where(*conditions)
        )).scalar_one()

        rows = (await db.execute(
            select(ConversationEntity)
            .where(*conditions)
            .order_by(ConversationEntity.update_time.desc(), ConversationEntity.id.desc())
            .offset((page - 1) * size)
            .limit(size)
        )).scalars().all()

        data = PageData(
            records=[ConversationDto.model_validate(row) for row in rows],
            total=int(total),
            size=size,
            current=page,
        )

    return Result.success(data=data)
