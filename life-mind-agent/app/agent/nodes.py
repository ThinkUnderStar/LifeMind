"""
Agent 图节点集合。

当前实现三个节点：
- relevant_user_memories：检索用户级长期记忆，替换系统提示词（读路径，失败不影响对话）
- save_human_session_memory：把本轮用户消息写入 MySQL + Milvus
- save_ai_session_memory：把本轮 AI 回复写入 MySQL + Milvus

设计约定：
1. 节点内双写，且两库要么都写、要么都不写：MySQL 用 flush 拿自增 id 与库时间（向量行的回溯键），
   事务保持打开，等 Milvus 写完再 commit；中途任一步抛异常就 rollback 并原样抛出，交给全局异常处理器。
   这意味着记忆写失败会让这一轮请求整体失败（前端收到错误、可整轮重试），而不是静默只存一半。
2. 节点手动开 MySQL 会话（session_scope）：图节点拿不到 FastAPI 的请求级 session，
   只有 config，只能自己管一段会话的事务边界。
3. 所有会话共用一张 collection，靠 user_id / conversation_id 做标量过滤。Aura 是
   「一个 agent 一张 collection」（aura_agent_{agent_id}_session_memory），会话量一大就会堆出
   成百上千个 collection，每个都要独立建索引并常驻 load，元数据和内存开销都很高。
4. 主键由 Milvus 生成（auto_id），节点不构造、不传 id：一条消息切多少块就有多少行，
   每行拿到自己的主键，不会互相覆盖。业务侧的回溯键是标量字段 message_id / chunk_index。
   代价是主键不承载业务含义，`upsert` 无从下手——节点重放会重复写行，要收口只能按
   message_id 过滤删除后重写，或从 MySQL 全量重建索引。
"""
import asyncio
import logging
from typing import List

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage
from langchain_core.runnables import RunnableConfig
from langgraph.types import Command
from pymilvus import CollectionSchema, DataType, FieldSchema
from pymilvus.milvus_client import IndexParams

from app.agent.prompts import SYSTEM_PROMPT_TEMPLATE
from app.component_manager.agent_component import ChatLlmWithToolsManager
from app.core.settings import settings
from app.db.milvus.client import get_milvus_client
from app.db.mysql import ChatMessageEntity
from app.db.mysql.session import session_scope
from app.db.postgresql.session import get_store
from app.rag.document_processor import llm_memory_processor
from app.rag.embedding import embedding_docs
from app.rag.vector_store import vector_store
from app.schemas.graph.state import State

logger = logging.getLogger(__name__)

# Milvus 的 VARCHAR max_length 按「字节」算而不是字符：一个中文字占 3 字节，
# 按字符数截断照样会超限并抛 ParamError，所以统一按 UTF-8 字节裁。
# 正常切分后的 chunk 远小于这个值，这里只是最后一道保险。
MEMORY_TEXT_MAX_BYTES = 16384

# 用户级长期记忆在 store 里的命名空间首段，第二段是 user_id。
# 写成常量是为了让「写记忆」和「搜记忆」用同一个值——这两处一旦不一致，
# 表现是「记忆写进去了但永远搜不到」，且不会报错。
USER_MEMORY_NAMESPACE = "users_memory"

# 每轮注入系统提示词的相关记忆条数上限。多了会挤占上下文且稀释重点，
# 语义检索按相似度降序，取前几条就够。
USER_MEMORY_TOP_K = 5

# collection 只需建一次；建表并发保护用锁，避免首个请求并发打进来重复建表
_collection_ready = False
_collection_lock = asyncio.Lock()

# 会话记忆 collection 的字段定义。写成模块级常量而不是函数里的局部变量：
# 它是 schema 的唯一来源，和 MEMORY_TEXT_MAX_BYTES、settings.MILVUS_DIMENSION 属于同一类东西——
# 数据，不是逻辑，摆在这里一眼能看全。
_SESSION_MEMORY_FIELDS = [
    # 主键交给 Milvus 生成（auto_id）：写入时不带 id，长消息切出的多块各自拿到
    # 独立主键，不存在「多块共用一个主键互相覆盖」的问题
    FieldSchema(name="id", dtype=DataType.INT64, is_primary=True, auto_id=True),
    FieldSchema(name="text", dtype=DataType.VARCHAR, max_length=MEMORY_TEXT_MAX_BYTES),
    FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=settings.MILVUS_DIMENSION),
    # 标量字段全部冗余存一份：检索时按 user_id 过滤、按 conversation_id 圈定范围，
    # 不必再去 MySQL 二次查询。message_id / chunk_index 是回溯键：
    # 按消息删除记忆、按序号拼回原文，都靠它们（主键是 Milvus 生成的大整数，不承载业务含义）
    FieldSchema(name="message_id", dtype=DataType.INT64),
    FieldSchema(name="chunk_index", dtype=DataType.INT32),
    FieldSchema(name="user_id", dtype=DataType.INT64),
    FieldSchema(name="conversation_id", dtype=DataType.INT64),
    FieldSchema(name="role", dtype=DataType.VARCHAR, max_length=16),
    FieldSchema(name="create_time", dtype=DataType.VARCHAR, max_length=32),
]


# ============================== 内部工具函数 ==================================


def _message_text(message: BaseMessage) -> str:
    """
    把消息内容规范化成纯文本。

    LangChain 的 content 可能是 str，也可能是多模态分块列表
    （[{"type": "text", ...}, {"type": "image_url", ...}]）。后者直接丢给 embedding 会报错，
    所以这里只抽文本段，图片/音频等非文本块不进向量库（原始内容在 MySQL 里仍完整保留）。

    :param message: 任意角色的消息对象
    :return: 纯文本内容，无文本段时返回空串
    """
    content = message.content
    if isinstance(content, str):
        return content

    parts: List[str] = []
    for block in content:
        if isinstance(block, str):
            parts.append(block)
        elif isinstance(block, dict) and block.get("type") == "text":
            parts.append(str(block.get("text", "")))

    return "\n".join(part for part in parts if part)


async def _save_session_memory(
        state: State,
        config: RunnableConfig,
        role: str,
        message_type: type,
) -> dict:
    """
    会话记忆写入的公共实现，人类消息与 AI 消息两个节点共用。

    流程（同一事务内）：定位消息 → 取 (user_id, conversation_id) → MySQL flush 出 id 与库时间 →
    交给 RAG 的 llm_memory_processor 切分 → 长度保险 → 确保 collection 存在 → embedding →
    写入 Milvus → commit。

    MySQL 用 flush 而不是先 commit：向量写失败时能整体回滚，不留「MySQL 有行、向量没有」的半截数据。
    任何一步抛异常都会 rollback 后原样抛出，交给全局异常处理器统一转成 Result 错误响应
    （前端收到失败可以整轮重试，比静默只写一半要好）。

    前置条件缺失（拿不到消息、内容为空、config 缺身份）不算异常，只记日志并返回空更新。

    :param state: 当前图状态
    :param config: 运行时配置，需带 configurable.user_id 与 conversation_id/thread_id
    :param role: 写入的角色标记，user / assistant
    :param message_type: 要保存的消息类型，HumanMessage / AIMessage
    :return: 状态更新字典；本节点不改状态，恒返回 {}
    """
    global _collection_ready

    # 倒序取最后一条目标消息：工具轮次等场景下 messages[-1] 未必就是本轮要存的那条
    message = next((m for m in reversed(state.messages) if isinstance(m, message_type)), None)
    if message is None:
        logger.warning("状态里没有 %s，跳过会话记忆写入", message_type.__name__)
        return {}

    # 不做 strip：原文就是原文，首尾空白也属于用户输入，落库后不该与应用层看到的版本不一致
    content = _message_text(message)
    if not content.strip():
        # 纯工具调用轮次的 AI 消息 content 为空，没有可回忆的信息，不必占一行向量
        logger.info("%s 内容为空，跳过会话记忆写入", role)
        return {}

    # 身份只认 config：user_id 由网关注入请求头后转成 config，conversation_id 就是 checkpointer
    # 的 thread_id（一个会话一条线程）。缺任何一个都放弃写入，绝不落到 default_user 之类的
    # 兜底空间里去污染别人的记忆
    configurable = config.get("configurable", {})
    user_id = configurable.get("user_id")
    conversation_id = configurable.get("conversation_id") or configurable.get("thread_id")

    if user_id is None or conversation_id is None:
        logger.error("会话记忆写入缺少 user_id/conversation_id，跳过：configurable=%s", configurable)
        return {}

    try:
        user_id, conversation_id = int(user_id), int(conversation_id)
    except (TypeError, ValueError):
        logger.error("user_id/conversation_id 不是合法整数，跳过：user_id=%r conversation_id=%r",
                     user_id, conversation_id)
        return {}

    async with session_scope() as db:
        entity = ChatMessageEntity(
            conversation_id=conversation_id,
            role=role,
            content=content,
        )
        db.add(entity)

        # flush 把 INSERT 发出去但不提交：先拿到自增 id 与库时间（向量行的回溯键），
        # 事务仍开着，后面 Milvus 失败才有得回滚
        await db.flush()
        await db.refresh(entity)

        try:
            # 切分交给 RAG 侧统一处理：长消息在这里变成多块，切分规则只有那一份
            documents = await llm_memory_processor(entity, user_id)
            if not documents:
                logger.warning("消息 message_id=%s 切分后无内容，放弃本次写入", entity.id)
                await db.rollback()
                return {}

            # 长度保险：Milvus 的 VARCHAR max_length 按字节算，中文按字符截断照样超限，
            # 所以先编码再按字节切。正常切分后的 chunk 远小于上限，这里只防单块异常超长
            truncated_chunks = 0
            for document in documents:
                raw = document.page_content.encode("utf-8")
                if len(raw) > MEMORY_TEXT_MAX_BYTES:
                    document.page_content = raw[:MEMORY_TEXT_MAX_BYTES].decode("utf-8", errors="ignore")
                    truncated_chunks += 1

            if truncated_chunks:
                # 截断是数据损失，必须留痕，否则将来「召回内容不全」查不出原因
                logger.warning("消息 message_id=%s 有 %s/%s 块超出 %s 字节被截断",
                               entity.id, truncated_chunks, len(documents), MEMORY_TEXT_MAX_BYTES)

            # 确保 collection 存在且已 load。锁里做双重检查：等锁期间别的协程可能已经建好了
            if not _collection_ready:
                async with _collection_lock:
                    if not _collection_ready:
                        milvus_client = get_milvus_client()
                        collection_name = settings.SESSION_MEMORY_COLLECTION

                        if not await milvus_client.has_collection(collection_name=collection_name):
                            # AUTOINDEX 由 Milvus 按数据量自动选索引，省掉手工调 nlist、
                            # 以及换环境后重建索引的麻烦
                            index_params = IndexParams()
                            index_params.add_index(field_name="vector", index_type="AUTOINDEX",
                                                   metric_type="COSINE")
                            await milvus_client.create_collection(
                                collection_name=collection_name,
                                schema=CollectionSchema(_SESSION_MEMORY_FIELDS,
                                                        description="会话记忆向量集合"),
                                index_params=index_params,
                            )
                            logger.info("会话记忆 collection 创建完成：%s", collection_name)

                        # 检索/写入前必须 load；对已 load 的集合重复调用是幂等的
                        await milvus_client.load_collection(collection_name=collection_name)
                        _collection_ready = True

            embed = await embedding_docs(documents)
            await vector_store(embed, settings.SESSION_MEMORY_COLLECTION)

            # 向量写完才提交：MySQL 与 Milvus 至少保证「要么都写、要么都不写」这个方向
            await db.commit()
        except Exception:
            # 回滚要在本会话内显式做：session_scope 退出只是 close，不替调用方做决定
            await db.rollback()
            logger.error("会话记忆写入失败，已回滚 MySQL：conversation_id=%s role=%s content_len=%s",
                         conversation_id, role, len(content), exc_info=True)
            raise

    logger.info("会话记忆写入成功：conversation_id=%s role=%s message_id=%s chunks=%s",
                conversation_id, role, entity.id, len(documents))
    return {}


# ================================ 对外节点 ====================================


async def relevant_user_memories(state: State, config: RunnableConfig) -> dict:
    # TODO 全量注入永久记忆
    return {"messages": [SystemMessage(content=prompt, id=old_system.id)]}


async def llm_node(state: State) -> Command:
    """
    调用用户交互大模型。

    根据模型返回里有没有 tool_calls 决定下一步走向：
    - 有工具调用 → run_tool（工具执行完再回到本节点）
    - 无工具调用 → save_ai_session_memory（这一轮答复定稿，落记忆）
    用 Command 而不是条件边，是因为走向依赖模型输出本身，写在节点里比拆成路由函数更直观。

    Args:
        state: 图状态，messages 携带完整对话历史。

    Returns:
        Command: update 写入本轮 AI 回复（add_messages 按 id 合并），goto 指定下一个节点。
    """
    response = None
    chat_llm_with_tools = ChatLlmWithToolsManager.get_chat_llm_with_tools()
    async for chunk in chat_llm_with_tools.astream(state.messages):
        response = chunk if response is None else response + chunk

    if response is None:
        # 流式一个 chunk 都没拿到：补一条空 AI 消息，避免后面拿 messages[-1] 时取到人类消息
        response = AIMessage(content="")

    if response.tool_calls:
        return Command(
            update={"messages": response},
            goto="run_tool"
        )
    else:
        return Command(
            update={"messages": response},
            goto="save_ai_session_memory"
        )


async def save_human_session_memory(state: State, config: RunnableConfig) -> dict:
    """
    保存本轮用户消息到 MySQL + Milvus。

    位置：START 之后、任何 LLM 调用之前。先落库再回答，保证「用户说了什么」不会因为
    后续节点抛异常而丢失。

    Args:
        state: 图状态，messages 里应含本轮 HumanMessage。
        config: 运行时配置，需带 configurable.user_id 与 conversation_id/thread_id。

    Returns:
        dict: 空更新，本节点只做外部写入，不改状态。

    Note:
        MySQL 先 flush 拿 id、Milvus 写完才 commit；任一步失败会回滚 MySQL 并把异常抛给全局处理器，
        即「这一轮要么两条库都写成功，要么都不写」。
    """
    return await _save_session_memory(state, config, role="user", message_type=HumanMessage)


async def save_ai_session_memory(state: State, config: RunnableConfig) -> dict:
    """
    保存本轮 AI 回复到 MySQL + Milvus。

    位置：llm_node 给出无工具调用的回复之后（工具轮次走 run_tool 回到 llm_node，只存最终答复）。

    Args:
        state: 图状态，messages 里应含本轮 AIMessage。
        config: 运行时配置，需带 configurable.user_id 与 conversation_id/thread_id。

    Returns:
        dict: 空更新，本节点只做外部写入，不改状态。

    Note:
        与 save_human_session_memory 共用同一套写入实现和同一个 collection，
        role 字段区分 user / assistant，检索时才能按角色过滤（例如只召回用户自己的话）。
    """
    return await _save_session_memory(state, config, role="assistant", message_type=AIMessage)
