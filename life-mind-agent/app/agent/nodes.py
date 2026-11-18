"""
Agent 图节点集合（N1–N16，共 16 个节点；拓扑与条件边见 graph.py）。

按职责分组：
- 入口与分流：save_human_session_memory（用户消息落库）、generate_task_bind_id（本轮对话的任务绑定 UUID，
  写进 State，供 AI 消息与任务共用）、gate（三分类 0违规/1纯对话/2任务）、
  violation_reply（违规写死回复，不调模型）
- 记忆装配：relevant_user_memories（用户级记忆，读 PG store）与 recall（本会话切片，读 Milvus）
  由 gate 的条件边并行触发，context_build 汇合后统一渲染进唯一那条 SystemMessage
- 任务分支：planner（结构化输出任务清单并落库）、assemble（挑 pending 任务置 running）、
  llm_node（带工具的执行模型，只看 task_messages）、approval_gate（人工确认，唯一 interrupt）、
  run_tool（回填 ToolMessage，只写 task_messages）、check_step（队列推进 + 写 result）、
  finalize（任务分支收尾压缩）
- 收尾与压缩：responder（给用户看的回复，不绑工具）、save_ai_session_memory（AI 回复落库）、
  compress_conversation（N16，全路径末端按 token 阈值裁剪 checkpoint 里的旧消息）

设计约定：
1. 节点内双写，且两库要么都写、要么都不写：MySQL 用 flush 拿自增 id 与库时间（向量行的回溯键），
   事务保持打开，等 Milvus 写完再 commit；中途任一步抛异常就 rollback 并原样抛出，交给全局异常处理器。
   这意味着记忆写失败会让这一轮请求整体失败（前端收到错误、可整轮重试），而不是静默只存一半。
2. 节点手动开 MySQL 会话（session_scope）：图节点拿不到 FastAPI 的请求级 session，
   只有 config，只能自己管一段会话的事务边界。
3. 一会话一 collection（物理隔离）：集合名 = {SESSION_MEMORY_COLLECTION}_{user_id}_{conversation_id}，
   写入侧首次落库时懒建表并 load，检索侧只认这一个集合；取错名字读不到别人的行。
   user_id / conversation_id 标量仍写进 metadata 作冗余校验，但不再承担隔离。
   代价是集合为重资源（独立索引 + 常驻 load），删除会话时要 drop、长期空闲可 release。
4. 主键由 Milvus 生成（auto_id），节点不构造、不传 id：一条消息切多少块就有多少行，
   每行拿到自己的主键，不会互相覆盖。业务侧的回溯键是标量字段 message_id / chunk_index。
   代价是主键不承载业务含义，`upsert` 无从下手——节点重放会重复写行，要收口只能按
   message_id 过滤删除后重写，或从 MySQL 全量重建索引。
"""
import logging
import uuid
from typing import List, Dict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, RemoveMessage, SystemMessage, ToolMessage
from langchain_core.messages.utils import count_tokens_approximately, trim_messages
from langchain_core.runnables import RunnableConfig

from langgraph.types import Command, interrupt
from pymilvus import CollectionSchema, DataType, FieldSchema
from pymilvus.milvus_client import IndexParams
from sqlalchemy import Select

from app.agent.prompts import VIOLATION_REPLY, SYSTEM_PROMPT_TEMPLATE, TASK_EXECUTION_SYSTEM_PROMPT
from app.component_manager.agent_component import ChatLlmWithToolsManager, GateLlmManager, PlannerLlmManager, \
    ChatLlmManager
from app.core.settings import settings
from app.db.milvus.client import get_milvus_client
from app.db.mysql import ChatMessageEntity
from app.db.mysql.entities import AgentTaskEntity
from app.db.mysql.session import session_scope
from app.db.postgresql.session import get_store
from app.rag import rag_flow
from app.rag.document_processor import llm_memory_processor
from app.rag.embedding import embedding_docs
from app.rag.vector_store import vector_store
from app.schemas.graph.state import State, TaskItem
from app.util.any_to_str import session_memories_to_str, user_memories_to_str, task_list_to_str, task_list_to_executor_prompt

from app.agent.tools import save_user_memory, update_tasks, recall_memory, web_search

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

# 每个会话一个 collection，按名字记住哪些已建表并 load。
# 用集合而不是单个 bool：新会话第一次写入时不能因为「别的会话建过了」就跳过建表。
# 不加锁：集合已按会话物理隔离，不同会话的首次写入落在不同的集合上，
# 一把全局锁只会把所有会话的首次写入串行化，挡不住任何真实冲突。
_ready_collections: set = set()

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


def _session_collection_name(user_id: int, conversation_id: int) -> str:
    """会话专属集合名：写入与检索共用这一个生成规则，取错名字就读不到别人的行。"""
    return f"{settings.SESSION_MEMORY_COLLECTION}_{user_id}_{conversation_id}"


# ============================== 内部工具函数 ==================================


async def _render_task_system_message(task_list: List[TaskItem]) -> SystemMessage:
    """把当前任务清单渲染成执行层的那条系统消息（task_messages[0]）。

    planner 初始化、assemble 每装配一步都会调它，用当时的 task_list 重新渲染：
    未完成任务带 id / 状态，已完成任务带结果，执行层模型据此知道还能调哪些任务。
    返回全新的 SystemMessage（无 id），调用方整表替换 task_messages[0] 即可。
    """
    rendered = await TASK_EXECUTION_SYSTEM_PROMPT.ainvoke(
        {"task_list": task_list_to_executor_prompt(task_list)}
    )
    return SystemMessage(content=rendered.to_string())


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
    global _ready_collections

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

    collection_name = _session_collection_name(user_id, conversation_id)

    async with session_scope() as db:
        # 任务绑定 UUID 只标在 AI 消息上：user / system 行一律留 NULL。
        # 这样 chat_message 里凡是 task_bind_id 非空的行，都是某一轮对话的 AI 回复，
        # agent_task.task_bind_id 与它相等的行就是这一轮产生的任务。
        entity = ChatMessageEntity(
            conversation_id=conversation_id,
            role=role,
            content=content,
            task_bind_id=state.task_bind_id if role == "assistant" else None,
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

            # 每个会话一个集合：首次写入时懒建表并 load，按集合名记住已就绪的那些。
            # 不加锁：不同会话写不同集合，同一会话的消息逐条写入，不会有跨集合的争用。
            if collection_name not in _ready_collections:
                milvus_client = get_milvus_client()

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
                _ready_collections.add(collection_name)

            embed = await embedding_docs(documents)
            await vector_store(embed, collection_name)

            # 向量写完才提交：MySQL 与 Milvus 至少保证「要么都写、要么都不写」这个方向
            await db.commit()
        except Exception:
            # 回滚要在本会话内显式做：session_scope 退出只是 close，不替调用方做决定
            await db.rollback()
            logger.error("会话记忆写入失败，已回滚 MySQL：conversation_id=%s role=%s content_len=%s",
                         conversation_id, role, len(content), exc_info=True)
            raise

    logger.info("会话记忆写入成功：collection=%s conversation_id=%s role=%s message_id=%s chunks=%s",
                collection_name, conversation_id, role, entity.id, len(documents))
    return {}


# ================================ 对外节点 ====================================


async def relevant_user_memories(state: State, config: RunnableConfig) -> dict:
    """
    检索用户级长期记忆，写成文本放进 State 字段，交给 context_build 统一渲染。

    位置：与 recall 并行（gate 条件边同时触发），两者写不同 State 字段，不冲突；
    汇合点 context_build 读本字段填进 SYSTEM_PROMPT_TEMPLATE 的 {memories}。
    本节点不碰 messages —— 渲染系统提示词是 N6 的职责，避免并行分支写同一 channel。

    取法：PG store 的 ("users_memory", user_id) 命名空间 asearch，不做语义检索
    （store 未配 index_config 时 query 会被忽略），按更新时间取前若干条。

    Note:
        1. limit=200 是取最近 200 条，不是「全量」；条目一多会把系统提示撑大，
           真按本轮提问检索需要先给 store 配 index_config。
        2. 这里没有 try/except：store 异常会直接冒到全局异常处理器，整轮对话失败，
           与「记忆读失败不影响回答」的 fail-open 约定相反。
        3. user_id 用 == "" 判空判不出 None，显式传 None 时会拼出 ("users_memory", "None")
           这个命名空间，表现是静默读不到记忆而不是报错。
        4. state.messages[0] 仍校验是不是 SystemMessage，但只用于前置检查，本节点不改它。

    :param state: 当前图状态
    :param config: 运行时配置，需带 configurable.user_id
    :return: {"relevant_user_memories": 文本}；前置条件缺失时是 {}
    """
    old_system = state.messages[0]
    if not isinstance(old_system, SystemMessage):
        logger.error("第一条消息不是系统提示此")
        return {}

    configurable = config.get("configurable", {})
    user_id = configurable.get("user_id","")
    if user_id == "":
        logger.error("未传入user_id")
        return {}

    name_space = ("users_memory",str(user_id))
    items = await get_store().asearch(name_space, limit=200)
    user_memories = user_memories_to_str(items, with_key=True, max_chars=None)

    return {"relevant_user_memories": user_memories}


async def llm_node(state: State) -> Dict:
    """
    调用带工具的执行模型，跑一个任务步。

    只把 task_messages（临时任务记忆）喂给模型，不读主 messages ——
    任务执行层看不到原始闲聊，结论也不会混进给用户看的历史。

    走向由 graph.py 的 route_after_llm 条件边按本节点写回的 AIMessage 判断：
    - 带 tool_calls → approval_gate → run_tool → 回到本节点
    - 不带 tool_calls → check_step（本任务收尾，推进队列）

    Args:
        state: 图状态，只读 task_messages。

    Returns:
        dict: {"task_messages": [...原消息, 本轮 AIMessage]}。task_messages 无 reducer，
        必须整表覆盖；不走 Command，分支由条件边决定。
    """
    response = None
    chat_llm_with_tools = ChatLlmWithToolsManager.get_chat_llm_with_tools()
    async for chunk in chat_llm_with_tools.astream(state.task_messages):
        # 这边的AIMessage对象相加等价于content拼接
        response = chunk if response is None else response + chunk

    if response is None:
        # 流式一个 chunk 都没拿到：补一条空 AI 消息，避免后面拿 messages[-1] 时取到人类消息
        response = AIMessage(content="")
    task_messages = [*state.task_messages, response]

    return {
        "task_messages": task_messages,
    }


async def generate_task_bind_id(state: State) -> dict:
    """为本轮对话生成任务绑定 UUID，写进 State.task_bind_id。

    位置：紧跟 save_human_session_memory 之后、gate 之前，即入口落库完成后、
    任何分支判断之前。这样三条路径（违规 / 纯聊天 / 任务）都带着同一个 UUID：
    - save_ai 落 AI 消息时把它写进 chat_message.task_bind_id（仅 assistant 行有值）；
    - planner 建任务时把它写进 agent_task.task_bind_id。

    两者相等即表示「这条任务由这条 AI 回复那一轮产生」，从而实现任务绑到对话而非会话。
    每轮生成一次（uuid4），一轮内所有任务共用一个值；不同轮次互不串。

    零副作用：只生成一个 UUID 字符串写进 State，不读库、不调模型、不改 messages。

    :param state: 当前图状态，只读不需要；签名符合节点约定
    :return: {"task_bind_id": "<uuid4 字符串>"}
    """
    task_bind_id = str(uuid.uuid4())
    logger.info("本轮任务绑定 UUID 生成：%s", task_bind_id)
    return {"task_bind_id": task_bind_id}


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
        与 save_human_session_memory 共用同一套写入实现和同一个会话集合，
        role 字段区分 user / assistant，检索时才能按角色过滤（例如只召回用户自己的话）。
    """
    return await _save_session_memory(state, config, role="assistant", message_type=AIMessage)

async def run_tool(state: State, config: RunnableConfig) -> Dict:
    """
    执行上一轮模型给出的工具调用，把每个结果包成 ToolMessage 回填到消息列表。

    位置：llm_node 判断有 tool_calls 时 goto 到这里，执行完再回到 llm_node，
    所以工具轮次可以连续多轮，直到模型不再要求调用工具为止。

    Args:
        state: 图状态，messages 的最后一条必须是带 tool_calls 的 AIMessage。
        config: 运行时配置，原样转交给工具。必须传，否则工具取不到 configurable.user_id，
                只会静默返回「未取到当前用户身份」，不报错也不落库。

    Returns:
        dict: {"task_messages": [...原消息, ToolMessage, ...]}，每个 tool_call 一条，
        顺序与调用顺序一致。写 task_messages 而非主 messages（后者是给用户看的历史）。

    Note:
        1. 确认环节用 interrupt 实现，前端 resume 必须回 {"choice": ...}：
           approve 按原参数执行、reject 不执行、edit 时取同级的 "edit" 字段替换内容。
           整个节点没有 try/except，resume 回来的不是 dict 会直接抛 TypeError。
        2. 未知工具不抛异常，只回一条说明：让模型知道这个工具不存在，
           比让整轮请求失败可控。
        3. 已知隐患：interrupt 在 for 循环里，而循环体内有写入副作用。resume 会从节点开头重放，
           一次返回多个 tool_call 时，前面已执行的工具会被重复执行（同一条记忆会写两次）。
        4. ToolMessage 的 tool_call_id 必须与 tool_call 的 id 严格对应，
           少回一条，下一轮上游就会以「tool_calls 没有对应响应」拒绝继续对话。
    """
    # 任务流水只进 task_messages（llm_node 把带 tool_calls 的 AIMessage 写在这里），
    # 不能读主 messages —— 那是给用户看的历史，两处不是同一个列表
    task_list = state.task_list
    task_messages = state.task_messages
    last_message = state.task_messages[-1] if state.task_messages else None
    last_ai_message = last_message if isinstance(last_message, AIMessage) else None
    tool_approval = state.tool_approval
    messages = []

    if not last_ai_message:
        logger.info("工具运行节点获取到的最后一条消息对象不为AI发的消息")
        return {}

    tool_calls = last_ai_message.tool_calls
    for tool_call in tool_calls:
        tool_id = tool_call.get("id")
        tool_name = tool_call.get("name")
        tool_args = tool_call.get("args")
        # ==============================================================================================================
        # 调用用户级记忆保存工具
        if tool_name == "save_user_memory":
            if tool_approval[str(tool_id)] == "approve":

                result = await save_user_memory.ainvoke(
                    tool_args,
                    config=config
                )
            elif tool_approval[str(tool_id)] == "reject":

                result = "用户拒绝将该内容添加进用户级记忆"
            elif tool_approval[str(tool_id)].startswith("edit"):

                result = await save_user_memory.ainvoke(
                    {
                        "content": tool_approval[str(tool_id)].split(":")[1]
                    },
                    config=config,
                )
            else:

                result = "用户的工具调用认证异常，不符合规范"

            messages.append(ToolMessage(tool_call_id=tool_id, content=result))

        # 新增任务或修改未动的任务
        elif tool_name == "update_tasks":
            # state 是 InjectedToolArg：模型看不到也不用传，必须放进 input dict 显式注入；
            # 用 kwargs 传 state= 不会生效（LangChain 只认 input dict 里的同名字段）。
            task_list = await update_tasks.ainvoke(
                {**tool_args, "state": state},
                config=config,
            )
            system_info = await _render_task_system_message(task_list)
            task_messages[0] = system_info
            messages.append(ToolMessage(tool_call_id=tool_id, content="已更新任务列表"))

        # 主动回忆本会话的历史内容：只读、非敏感，结果作为工具返回值回填给模型
        elif tool_name == "recall_memory":
            result = await recall_memory.ainvoke(tool_args, config=config)
            messages.append(ToolMessage(tool_call_id=tool_id, content=result))

        # 联网搜索：只读、非敏感；能否真正联网由工具内部读 config 开关决定
        elif tool_name == "web_search":
            result = await web_search.ainvoke(tool_args, config=config)
            messages.append(ToolMessage(tool_call_id=tool_id, content=result))

        # 无匹配工具的兜底操作
        else:
            messages.append(ToolMessage(tool_call_id=tool_id,content="工具库中并没有你想调用的工具"))
        # ==============================================================================================================

    # ToolMessage 必须回写 task_messages：下一轮 llm_node 读它才能看到工具结果；
    # 写进主 messages 会污染给用户看的历史。task_messages 无 reducer，整表覆盖
    return {
        "task_messages": [*task_messages, *messages],
        "task_list": task_list,
    }

async def gate(state: State) -> Dict:
    """意图分流：把本轮用户原话交给 gate 模型做三分类，只写 type 字段。

    判据（0违规 / 1纯对话 / 2任务）写在 GateOutput 的 Field(description) 里，
    本节点用 with_structured_output 拿实例、取 out.type，不做任何分流判断 ——
    分流是 graph.py 里 route_after_gate 条件边的事。零副作用，不写库、不改 messages。
    """
    human_message = state.messages[-1]
    gate_llm = GateLlmManager.get_gate_llm()
    output = await gate_llm.ainvoke(human_message)

    return {"type": output.type}

async def violation_reply(state: State) -> Dict:
    """违规轮次的回复：写死 VIOLATION_REPLY，不调模型、不复述违规原因。

    只往主 messages 追加一条 AIMessage，随后直接走 save_ai 落库；
    不进记忆装配和任务分支，因此不会产生会话召回或任务记录。
    """
    return {
        "messages": AIMessage(content=VIOLATION_REPLY)
    }

async def recall(state: State, config: RunnableConfig) -> Dict:
    """召回本会话的向量切片，渲染成纯文本写回 State（不碰 messages）。"""
    configurable = config.get("configurable", {})
    user_id = configurable.get("user_id")
    conversation_id = configurable.get("conversation_id") or configurable.get("thread_id")
    if user_id is None or conversation_id is None:
        logger.error("会话回忆缺少 user_id/conversation_id，跳过：configurable=%s", configurable)
        return {}

    try:
        collection_name = _session_collection_name(int(user_id), int(conversation_id))
    except (TypeError, ValueError):
        logger.error("会话回忆的 user_id/conversation_id 不是整数，跳过：%r / %r", user_id, conversation_id)
        return {}

    documents = await rag_flow.rag_search(
        question=_message_text(state.messages[-1]),
        collection_name=collection_name,
    )
    return {"relevant_session_memories": session_memories_to_str(documents)}

async def context_build(state: State) -> Dict:
    """汇合两个并行记忆分支，把结果渲染进唯一那条 SystemMessage。

    读 relevant_user_memories / relevant_session_memories 两个字段，填进
    SYSTEM_PROMPT_TEMPLATE 的 {memories} / {session_recall}；沿用原 SystemMessage 的 id
    覆盖而非追加，保证项目恒一条 system（add_messages 按 id 合并，id 变了会多出第二条）。

    是否纯对话不在这里判断 —— 那是 graph.py 里 route_after_context 条件边的职责，
    按 state.type 分 responder（1）或 planner（2）。本节点不碰 messages 里的其他消息。
    """
    relevant_user_memories = state.relevant_user_memories
    relevant_session_memories = state.relevant_session_memories

    system_message = state.messages[0]

    # ainvoke 是异步的，必须 await；返回 StringPromptValue，要 .to_string() 才是 str
    system_prompt = await SYSTEM_PROMPT_TEMPLATE.ainvoke(
        {
            "memories": relevant_user_memories,
            "session_recall": relevant_session_memories,
        }
    )

    new_system_message = SystemMessage(
        content=system_prompt.to_string(),
        id=system_message.id
    )

    return {
        "messages": [new_system_message]
    }

async def assemble(state: State) -> Dict:
    """从任务清单里挑下一条 pending 任务，置为 running 并装成执行模型的输入。

    双写：MySQL 先改 status 并 commit，再改 State 里的 TaskItem，最后把任务目标作为
    HumanMessage 追加进 task_messages（只放临时记忆，不进主 messages）。
    running_task_id 记下当前任务 id，供 check_step 收尾时定位。

    每装配一步都会用最新 tasks 重渲染 task_messages 第一条 SystemMessage（热更新任务清单），
    其余执行流水保留：模型每步都看得到未完成任务及其 id、已完成任务的结论。

    task_list 是普通 List 无 reducer，这里原地改对象后整表返回；找不到库行或没有 pending
    任务时返回 {}，不会破坏现有状态。
    """
    tasks = state.task_list
    async with session_scope() as db:
        for i,task in enumerate(tasks):
            if task.status == "pending":

                query = Select(AgentTaskEntity).where(AgentTaskEntity.id == task.id)
                db_task = (await db.execute(query)).scalar_one_or_none()

                if not db_task:
                    logger.error(f"数据库中没有id为${task.id}的任务")
                    return {}

                db_task.status = "running"
                await db.commit()

                tasks[i].status = "running"

                # 热更新第一条系统消息：用刚改过的 tasks（本条已是 running）重渲染任务清单，
                # 让执行模型每步都看到最新的未完成/已完成分组和各自 id；其余执行流水原样保留。
                # task_messages 无 reducer，整表覆盖；state.task_messages 为空时 rest 为空。
                rest = state.task_messages[1:] if state.task_messages else []
                system_message = await _render_task_system_message(tasks)
                task_messages = [system_message, *rest, HumanMessage(
                    content=task.goal,
                )]

                return {
                    "task_list": tasks,
                    "task_messages": task_messages,
                    "running_task_id": task.id,
                }

    logger.warning("任务已全部结束")
    return {}

async def planner(state: State,config: RunnableConfig) -> Dict:
    """按用户原话规划任务清单，落库后写进 State 并初始化任务临时记忆。

    PlannerOutput 的口径是「输出完整清单、整表覆盖」，一条 goal 一行；本节点只负责
    把每条 goal 插成 agent_task 的 pending 行（conversation_id 作会话归属，
    task_bind_id 绑到本轮 AI 回复那条对话），refresh 拿到自增 id 后另建纯数据 TaskItem
    —— ORM 实体不进 State。

    task_bind_id 来自入口 generate_task_bind_id 写进 State 的本轮 UUID，与随后 save_ai
    落库的那条 AI 消息共用同一个值，据此把任务绑到具体对话而不只是会话。

    落库成功后把 State.task_list 整表返回（planning 会重建整张表，不追加到旧表），
    task_messages 重置为一条渲染好任务清单的 TASK_EXECUTION_SYSTEM_PROMPT 系统消息，
    后续执行只读这份临时记忆；清单由 assemble 每步热更新。
    """
    configurable = config.get("configurable", {})
    conversation_id = configurable.get("conversation_id") or configurable.get("thread_id")
    task_bind_id = state.task_bind_id
    task_list = state.task_list

    planner_llm = PlannerLlmManager.get_planner_llm()
    output = await planner_llm.ainvoke(state.messages[-1])

    # 添加任务
    async with session_scope() as db:
        for task_content in output.task_list:
            try:
                task = AgentTaskEntity(
                    conversation_id=conversation_id,
                    task_bind_id=task_bind_id,
                    goal=task_content,
                    status="pending",
                )
                db.add(task)
                await db.commit()
                await db.refresh(task)

                # ORM 实体只留在 db 会话里；额外创建纯数据对象放进 State 的任务清单
                task_list.append(TaskItem(
                    id=task.id,
                    conversation_id=conversation_id,
                    task_bind_id=task_bind_id,
                    goal=task_content,
                    status="pending",
                ))
            except Exception as e:
                await db.rollback()
                logger.error(e)

    # 初始化任务临时对话记忆。planner 是任务分支入口，这里 state.task_messages 通常是空的
    # （无需保留历史），所以直接以渲染好的系统消息作为第一条即可。旧写法是把静态文案用
    # [*state.task_messages, ...] 追加，现在文案改成带清单的模板，必须经 helper 渲染成 str。
    system_message = await _render_task_system_message(task_list)
    task_messages = [system_message]

    return {
        "task_list": task_list,
        "task_messages": task_messages,
    }


async def approval_gate(state: State,config: RunnableConfig) -> Dict:
    """人工确认环节"""
    # 与 run_tool 对齐：带 tool_calls 的 AIMessage 在 task_messages，不在主 messages
    last_message = state.task_messages[-1] if state.task_messages else None
    last_ai_message = last_message if isinstance(last_message, AIMessage) else None
    configurable = config.get("configurable", {})
    agent_permission = configurable.get("agent_permission", "normal")
    tool_approval = {}

    # 敏感工具集合
    sensitive_tools = [
        "save_user_memory",
    ]

    if not last_ai_message:
        logger.info("工具运行节点获取到的最后一条消息对象不为AI发的消息")
        return {}

    tool_calls = last_ai_message.tool_calls
    for tool_call in tool_calls:
        tool_name = tool_call.get("name")
        tool_id = tool_call.get("id")
        tool_args = tool_call.get("args")

        # 完全信任agent的判断逻辑
        if agent_permission == "full" and tool_name in sensitive_tools:
            tool_approval[str(tool_id)] = "approve"
            continue

        # ==============================================================================================================
        # 调用用户级记忆保存工具
        if tool_name == "save_user_memory":
            user_choice = interrupt({
                "question": f"是否同意将:\n {tool_args["content"]} \n添加进用户级记忆？",
                "options": ["approve", "reject", "edit"]
            })

            if user_choice["choice"] in ["approve","reject"]:
                tool_approval[str(tool_id)] = user_choice["choice"]

            elif user_choice["choice"] == "edit":
                tool_approval[str(tool_id)] = "edit:"+user_choice["edit"]

            else:
                logger.warning("用户的确认信息异常")
                tool_approval[str(tool_id)] = "reject"

        # ==============================================================================================================

    return {"tool_approval": tool_approval}

async def check_step(state: State) -> Dict:
    """收尾刚跑完的那条任务，再决定队列是否还有下一条。

    取 running_task_id 定位任务，把 task_messages 最后一条（执行模型的简短结果文本）
    写进 agent_task.result 和 State 的 TaskItem.result，状态置 done —— done 只表示
    「本条执行已结束」，成败都在 result 文本里，不做语义判定、不调模型。

    然后看还有没有 pending：有则 has_tasks=True（条件边回 assemble），否则 False
    （去 finalize）。库里查不到该行时只记日志、跳过写库，但 State 侧照常推进，避免卡队列。

    本条置 done 后会热更新 task_messages 第一条系统消息，把最新的完成态与 result
    渲染进清单快照。这一步不能省：最后一条任务完成后直接去 finalize、不再回 assemble，
    若只靠 assemble 热更新，完成态就永远进不了系统消息。
    """
    over_task_id = state.running_task_id
    task_list = state.task_list
    result = state.task_messages[-1].content

    async with session_scope() as db:
        query = Select(AgentTaskEntity).where(AgentTaskEntity.id == over_task_id)
        db_task = (await db.execute(query)).scalar_one_or_none()

        if not db_task:
            logger.error(f"数据库中没有id为${over_task_id}的任务")
        else:
            db_task.status = "done"
            db_task.result = result
            await db.commit()

    # 先完整更新本条状态，再单独判断队列：不在同一个循环里提前 return，
    # 否则本条排在 pending 之后时 State 侧的 done / result 会被跳过，热更新拿到旧状态。
    for i, task in enumerate(task_list):
        if task.id == over_task_id:
            task_list[i].status = "done"
            task_list[i].result = result

    has_pending = any(
        task.status == "pending" and task.id != over_task_id
        for task in task_list
    )

    # 用刚置好的最新清单热更新第一条系统消息，其余执行流水（含本条结果消息）保留。
    rest = state.task_messages[1:] if state.task_messages else []
    system_message = await _render_task_system_message(task_list)
    task_messages = [system_message, *rest]

    return {
        "has_tasks": has_pending,
        "task_list": task_list,
        "task_messages": task_messages,
    }

async def finalize(state: State) -> Dict:
    """任务分支收尾：把任务清单压成文本、清空任务态，交给 responder 出最终回复。

    注意（已知待修，用户要求暂不动）：
    - 任务清单被塞成一条 AIMessage 进主 messages，会被 save_ai 当成回复落库展示；
      不过 save_ai 取的是最后一条 AIMessage，responder 的回复排在它后面，实际落库的是回复。
    - has_tasks 重置为 True，与 route_after_step 的「False 才进 finalize」判据语义冲突；
      但 finalize 之后不再回到该条件边，本轮不受影响。
    这里只清 State 里的任务态字段，不动 agent_task 表（结论已由 check_step 落库）。
    """
    tasks = state.task_list
    tasks_result = task_list_to_str(tasks)

    return {
        "messages": AIMessage(content=tasks_result),
        "relevant_user_memories": "",
        "relevant_session_memories": "",
        "task_list": [],
        "tool_approval": {},
        "task_messages": [],
        "has_tasks": True,
        "running_task_id": None
    }

async def responder(state: State, config: RunnableConfig) -> Dict:
    """ai对话节点"""
    chat_llm = ChatLlmManager.get_chat_llm()
    response = None

    async for chunk in chat_llm.astream(state.messages):
        # 这边的AIMessage对象相加等价于content拼接
        response = chunk if response is None else response + chunk

    if not response:
        response = AIMessage(content="")

    return {
        "messages": response,
    }


# ================================ N16 · 对话压缩节点 ================================

# 主 messages 保留的 token 上限。按所用模型的上下文窗口留 25~30% 余量来取，
# 别直接顶到窗口上限；压完会明显低于阈值，所以天然只压一次、不会每轮重复压。
CONVERSATION_MAX_TOKENS = 4000


async def compress_conversation(state: State) -> Dict:
    """按 token 阈值压缩主 messages：只删 PG checkpoint 里的旧消息副本。

    位置：全路径末端（save_ai_session_memory 之后、END 之前），纯聊天 / 违规 /
    任务三条分支都汇聚到这里。此时本轮回复已经落完 MySQL + Milvus，删除只影响
    下一轮带进模型的上下文，不会干扰落库节点倒序取 AIMessage 的逻辑。

    设计取舍：
    1. 只做「裁剪」，不做「摘要」。摘要要么额外调一次模型（拖慢已经结束的这轮、
       多一个失败点），要么要加 State 新字段 + 系统提示词第三占位符 + N6 渲染；
       而 N5 recall 本来就负责把本会话较早片段从 Milvus 捞回来，数据并不丢，
       缺的只是一段连贯梗概，不值得为此扩大改动面。
    2. 零副作用：不写库、不调模型、不 interrupt，也绝不返回 task_list / has_tasks
       等任务态字段（压缩节点不负责重置任务状态）。
    3. 项目恒一条 SystemMessage：include_system=True 保留它，且按 id 差集删除，
       双保险保住 messages[0]；start_on="human" 保证裁剪后从用户消息开始。
    4. 只删 checkpoint 的 messages 副本，MySQL chat_message（展示层）与 Milvus
       会话切片都不动；展示层历史与会话召回因此完全不受影响。

    Note:
        1. 主 messages 里不该出现工具流水（都在 task_messages）。万一出现，本节点
           直接跳过压缩：拆散 AIMessage(tool_calls) 与 ToolMessage 会让上游模型
           拒绝继续对话。
        2. RemoveMessage 的 id 必须来自当前 messages 里真实存在的消息；删不存在的
           id 会抛 ValueError。LangGraph 的 add_messages 会给无 id 的消息补 uuid，
           这里仍显式跳过无 id 的消息，宁可少删也不冒异常。
        3. 单条消息本身就超过阈值时，allow_partial=False 不会截断内容，而是把
           这条消息整体排除在裁剪结果之外 —— 实测过：一条超长用户消息会被直接
           删掉。所以下面额外守住「本轮最近一条消息必须留下」和「保留结果里
           必须有用户消息」，任一不满足就整轮跳过：宁可不压，也不删用户原话。
        4. 依赖 State.messages 上的 add_messages reducer 才能生效（当前 State 已有）。

    :param state: 当前图状态，messages 是主对话历史
    :return: 未超阈值 / 含工具流水 / 无法安全裁剪时返回 {}；
             否则返回 {"messages": [RemoveMessage, ...]}，只删被裁掉的旧消息
    """
    messages = state.messages
    if not messages:
        return {}

    # 防御：主 messages 一旦混入工具流水就不压，避免把成对的消息拆开
    if any(isinstance(m, ToolMessage) or getattr(m, "tool_calls", None) for m in messages):
        logger.warning("主 messages 含工具流水，跳过本轮压缩，避免拆散 tool_calls / ToolMessage")
        return {}

    total_tokens = count_tokens_approximately(messages)
    if total_tokens <= CONVERSATION_MAX_TOKENS:
        return {}

    try:
        kept = trim_messages(
            messages,
            max_tokens=CONVERSATION_MAX_TOKENS,
            strategy="last",
            token_counter=count_tokens_approximately,
            include_system=True,
            start_on="human",
            allow_partial=False,
        )
    except (ValueError, TypeError) as exc:
        logger.warning("对话压缩无法安全裁剪，本轮跳过：%s", exc)
        return {}

    # 裁剪结果必须留下本轮最近一条消息，且至少还要有用户消息。
    # 否则说明阈值太小 / 单条消息超长，压下去会丢掉用户刚说的话，整轮放弃。
    last_id = getattr(messages[-1], "id", None)
    if last_id and not any(getattr(m, "id", None) == last_id for m in kept):
        logger.warning("裁剪会删掉本轮最近一条消息，跳过本轮压缩")
        return {}
    if not any(isinstance(m, HumanMessage) for m in kept):
        logger.warning("裁剪结果不含用户消息，跳过本轮压缩")
        return {}

    kept_ids = {m.id for m in kept if getattr(m, "id", None)}
    to_remove = [
        RemoveMessage(id=m.id)
        for m in messages
        if getattr(m, "id", None) and m.id not in kept_ids
    ]
    if not to_remove:
        return {}

    logger.info(
        "对话压缩：%s 条约 %s token → 保留 %s 条，删除 %s 条",
        len(messages), total_tokens, len(kept), len(to_remove),
    )
    return {"messages": to_remove}

