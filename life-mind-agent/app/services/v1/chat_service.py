"""聊天与会话记忆相关的流式实现。

三个流式接口（发送 / 中断恢复 / 时间回溯）共用同一段 astream_events 消费逻辑，
区别只在「喂给图什么输入」和「config 里的快照参数」：

- send      : input={"messages": [...]}，config 带本轮开始前的 checkpoint（写进 user 消息行）
- resume    : input=Command(resume=...)，图从 interrupt 处继续，不新增用户消息
- backtrack : input={"messages": [新 HumanMessage]}，config 带 checkpoint_id 从历史快照分叉

向外推三类东西：
1. `responder` 节点产生的文本——这是给用户看的话；
2. 服务插件工具的开始/结束，以及「该打开哪个面板、定位到哪条数据」；
3. 人工确认中断与结束标记。

第 2 类必须按工具归属过滤：内置工具（记忆、联网搜索）只在 agent 内部发生，
推给前端只会多出一种「无插件」的空状态要处理。任务分支里 `llm_node` 也在流式产出，
但那是任务执行的中间流水（只该落到 agent_task.result），不是给用户看的话，
所以文本必须按节点名过滤，不能见 chunk 就发。
"""
import json
import logging
from typing import Any, AsyncGenerator, Dict, Optional, Tuple

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph.state import CompiledStateGraph
from langgraph.types import Command
from sqlalchemy import func, select, update

from app.agent.nodes import delete_session_memory_since, drop_session_memory_collection
from app.agent.service_catalog import plugin_of_tool, tool_label, ui_panel_of
from app.client.base import Identity
from app.db.mysql.entities import AgentTaskEntity, ChatMessageEntity
from app.db.mysql.session import session_scope
from app.db.postgresql.session import get_saver
from app.exception.exceptions import BusinessException
from app.schemas.graph.state import State
from app.schemas.web.request import BacktrackDto, ChatDto, ResumeDto
from app.schemas.web.response import PendingInterruptDto
from app.schemas.web.result import Result
from app.services.v1.session_service import require_owned_conversation

logger = logging.getLogger(__name__)

# 主会话那条 SystemMessage 的固定 id。
# context_build 渲染完会沿用这个 id 写回，add_messages 按 id 合并，
# 因此整条会话始终只有一条 system，不会每轮追加出第二条。
SYSTEM_MESSAGE_ID = "life-mind-system"


def _sse(event_type: str, data: Any = None) -> str:
    """
    把一条事件包成 SSE 帧。

    统一成 {"type": ..., "data": ...} 的 JSON，前端按 type 分派：
    text / tool / ui / interrupt / done / error。文本片段也放在 data 里，
    而不是裸字符串，避免前端要为不同事件写两套解析。

    :param event_type: 事件类型
    :param data: 事件负载，None 时不输出该字段（如 done）
    :return: 符合 text/event-stream 的字符串帧
    """
    payload: Dict[str, Any] = {"type": event_type}
    if data is not None:
        payload["data"] = data
    # ensure_ascii=False 保留中文原样；default=str 兜住 interrupt 里可能出现的非 JSON 对象
    return f"data: {json.dumps(payload, ensure_ascii=False, default=str)}\n\n"


def _build_config(
        identity: Identity,
        conversation_id: int,
        enable_web_search: int = 0,
        from_checkpoint_id: Optional[str] = None,
        fork_checkpoint_id: Optional[str] = None,
        enabled_plugins: str = "",
) -> Dict[str, Any]:
    """
    构造 LangGraph 的运行配置。

    两个快照参数含义不同，别混用：
    - from_checkpoint_id：本轮开始前的快照，只用于写进 user 消息行（回溯的分叉依据）；
    - fork_checkpoint_id：让图从该快照分叉继续（LangGraph 的 time travel），只有回溯会传。

    :param identity: 当前用户身份（ID + 角色 + 范围），三个值都进 configurable
    :param conversation_id: 会话ID，同时作为 thread_id
    :param enable_web_search: 本轮联网开关，放运行时 config，模型改不了
    :param from_checkpoint_id: 要记录到消息行上的父快照
    :param fork_checkpoint_id: 要作为父节点分叉的快照
    :param enabled_plugins: 网关注入的插件标识串，llm_node 据此决定绑定哪些服务工具
    :return: RunnableConfig 字典
    """
    configurable: Dict[str, Any] = {
        "user_id": identity.user_id,
        # 角色与范围随身份一起进运行时上下文，服务工具调下游时原样透传。
        # 不写死在 client 层：那等于 agent 用一个固定权限的账号代所有人操作，
        # 用户是 admin 时调不动管理接口，用户只有 read 时反而可能超权。
        "user_roles": identity.roles,
        "user_scopes": identity.scopes,
        # conversation_id 与会话主键同值；thread_id 单独给一份，
        # 兼容节点里 conversation_id or thread_id 的取法
        "conversation_id": conversation_id,
        "thread_id": str(conversation_id),
        "enable_web_search": enable_web_search,
        # 插件集合放在 configurable 而不是 State：它属于"这次请求的运行时上下文"，
        # 放进 State 会随 checkpoint 粘住，用户停用插件后旧快照里还留着旧集合
        "enabled_plugins": enabled_plugins or "",
    }
    if from_checkpoint_id:
        configurable["from_checkpoint_id"] = from_checkpoint_id
    if fork_checkpoint_id:
        configurable["checkpoint_id"] = fork_checkpoint_id
    return {"configurable": configurable}


async def _load_thread_state(
        graph: CompiledStateGraph,
        config: Dict,
) -> Tuple[bool, Optional[str]]:
    """
    读取线程当前状态，一次查询拿两样东西。

    原本只有「是否有历史」这一个用途（决定要不要补 SystemMessage）；
    回溯需要知道本轮开始前的 checkpoint_id，两件事都来自同一个 aget_state，
    分成两次查没有意义。

    :param graph: 编译后的图
    :param config: 带 thread_id 的运行配置
    :return: (是否已有消息, 当前 checkpoint_id)；查询失败按「无历史、无快照」处理
    """
    try:
        snapshot = await graph.aget_state(config)
    except Exception:
        # 查不到不代表不能聊，交给后续节点自己处理；这里只决定要不要补 system
        logger.warning("读取会话状态失败，按首轮处理", exc_info=True)
        return False, None

    if snapshot is None:
        return False, None

    values = getattr(snapshot, "values", None) or {}
    has_history = bool(values.get("messages"))

    checkpoint_id: Optional[str] = None
    try:
        configurable = (getattr(snapshot, "config", None) or {}).get("configurable") or {}
        checkpoint_id = configurable.get("checkpoint_id")
    except Exception:
        checkpoint_id = None

    return has_history, checkpoint_id


async def _stream_graph(
        graph: CompiledStateGraph[State],
        graph_input: Any,
        config: Dict[str, Any],
        scene: str,
) -> AsyncGenerator[str, None]:
    """
    消费图的流式事件并转成 SSE 帧。

    三个入口（send / resume / backtrack）只有 graph_input 不同，事件处理完全一致，
    集中在这里，避免三处各写一份、某一处忘了过滤 llm_node 就把任务流水泄给用户。

    事件类型：
    - text      普通的回复片段，前端追加显示
    - tool      服务插件工具开始/结束，前端可显示「正在创建事项」
    - ui        该打开哪个服务面板以及定位到什么数据，前端据此渲染子页面
    - interrupt approval_gate 触发人工确认，前端弹窗后用恢复接口继续
    - done      本轮结束
    - error     执行失败，前端提示

    tool 与 ui 的取舍：内置工具（联网搜索、记忆检索）不推这两个事件。
    它们是 agent 自己的能力，不属于任何插件，推了只会让前端多一种要处理的空插件状态。
    `plugin_of_tool` 返回 None 即跳过。

    :param graph: 已编译的图
    :param graph_input: 图输入，普通字典或 Command(resume=...)
    :param config: 运行配置
    :param scene: 场景名，只用于错误日志定位
    :yield: SSE 帧字符串
    """
    try:
        async for event in graph.astream_events(
                input=graph_input,
                config=config,
                version="v2",
        ):
            event_name = event.get("event")
            metadata = event.get("metadata") or {}

            # 只推 responder 的 token：gate / planner / llm_node 的流一律丢弃
            if event_name == "on_chat_model_stream" and metadata.get("langgraph_node") == "responder":
                chunk = event.get("data", {}).get("chunk")
                content = getattr(chunk, "content", "")
                if content:
                    yield _sse("text", content)

            # 服务插件工具：开始与结束成对推送，两端带同一个 id，前端据此配对
            # （开始显示进度、结束收起）。只给工具名派生的动作名，不给入参——
            # 入参里有用户的原始话术，前端用不上，没必要复制一份进事件流。
            if event_name in ("on_tool_start", "on_tool_end"):
                tool_name = event.get("name") or ""
                plugin_key = plugin_of_tool(tool_name)

                # 内置工具（记忆、联网搜索、任务清单）不属于任何插件，不推
                if plugin_key:
                    run_id = str(event.get("run_id") or "")
                    started = event_name == "on_tool_start"
                    yield _sse("tool", {
                        "id": run_id,
                        "phase": "start" if started else "end",
                        "plugin": plugin_key,
                        "action": tool_label(tool_name),
                    })

                    # 面板只在开始推一次：这时参数齐全，能定位到具体日期或具体那条数据。
                    # 结束时不重推，否则前端同一块面板会被刷新两遍，用户看到闪一下
                    if started:
                        panel = ui_panel_of(tool_name, (event.get("data") or {}).get("input"))
                        if panel:
                            yield _sse("ui", {"id": run_id, **panel})

            # 人工确认中断：把确认信息原样交给前端，由前端决定 approve / reject
            if event_name == "on_chain_stream":
                chunk = event.get("data", {}).get("chunk")
                if isinstance(chunk, dict) and "__interrupt__" in chunk:
                    interrupts = chunk.get("__interrupt__") or []
                    value = getattr(interrupts[0], "value", None) if interrupts else None
                    yield _sse("interrupt", value)

        yield _sse("done")

    except Exception:
        logger.exception(
            "%s 流式执行失败：configurable=%s",
            scene, config.get("configurable"),
        )
        yield _sse("error", "对话执行失败，请稍后重试")


async def assert_has_pending_interrupt(graph: CompiledStateGraph, conversation_id: int) -> None:
    """
    确认这条线程确实停在某个 interrupt 上，供 resume 前校验。

    为什么要单独查一次：`Command(resume=...)` 在「没有挂起节点」的线程上不会有意义，
    图会拿一个空输入继续跑（或直接报错），用户看到的是"点了同意但什么都没发生"。
    更常见的是前端重复提交同一个确认，第二次其实已经被处理过了。

    校验必须在路由里 await，不能放在流式生成器内部：生成器被 StreamingResponse 迭代时
    响应头已经发出去了，那时抛异常只能变成流里的 error 帧，前端拿不到 400。

    :param graph: 编译后的图
    :param conversation_id: 会话ID，即 thread_id
    :raises BusinessException: 线程不存在或没有挂起的节点
    """
    try:
        snapshot = await graph.aget_state({"configurable": {"thread_id": str(conversation_id)}})
    except Exception:
        logger.warning("恢复前查询线程状态失败：conversation_id=%s", conversation_id, exc_info=True)
        raise BusinessException(message="会话状态暂不可用，请稍后重试", code=500)

    # next 是「下一步要执行的节点」；interrupt 挂起时指向被中断的那个节点
    pending = getattr(snapshot, "next", None) if snapshot is not None else None
    if not pending:
        raise BusinessException(message="当前没有待确认的操作，可能已经处理过了", code=400)


async def get_pending_interrupt_service(
        graph: CompiledStateGraph,
        conversation_id: int,
) -> Result[PendingInterruptDto]:
    """
    查询这条会话当前是否停在人工确认上，并把中断载荷一并返回。

    与 `assert_has_pending_interrupt` 的区别：那个用于 resume 前拦截非法恢复，
    没有就抛 400；这里用于前端重连时「问一句」，没有中断是正常状态，不能报错。

    取载荷的路径是 snapshot.tasks[].interrupts[]，不是 `__interrupt__`——
    后者只在流式事件的 chunk 里出现，静态查询拿不到。

    :param graph: 编译后的图
    :param conversation_id: 会话ID，即 thread_id
    :return: has_pending + 中断载荷；查询失败按「没有挂起」返回，不打断前端
    """
    try:
        snapshot = await graph.aget_state({"configurable": {"thread_id": str(conversation_id)}})
    except Exception:
        logger.warning("查询挂起中断失败：conversation_id=%s", conversation_id, exc_info=True)
        return Result.success(data=PendingInterruptDto(has_pending=False, interrupt=None))

    if snapshot is None or not getattr(snapshot, "next", None):
        return Result.success(data=PendingInterruptDto(has_pending=False, interrupt=None))

    interrupt_value: Any = None
    # 同一线程只会在一个节点上挂起；多个 task 都带 interrupts 时取第一个即可
    for task in getattr(snapshot, "tasks", ()) or ():
        interrupts = getattr(task, "interrupts", None) or ()
        if interrupts:
            interrupt_value = getattr(interrupts[0], "value", None)
            break

    return Result.success(data=PendingInterruptDto(has_pending=True, interrupt=interrupt_value))


async def chat_with_agent_service(
        graph: CompiledStateGraph[State],
        identity: Identity,
        chat_dto: ChatDto,
        enabled_plugins: str = "",
) -> AsyncGenerator[str, None]:
    """
    聊天接口的流式实现：把一轮用户消息喂给图，边跑边把回复推给前端。

    首轮补一条空的 SystemMessage 占位：context_build 会读 messages[0] 的 id 后整条覆盖，
    没有这条会出现第二条 system。已有历史时不补。

    同时把「本轮开始前的 checkpoint_id」塞进 config，由 save_human 写进这条用户消息行，
    供之后的时间回溯定位分叉点。

    :param graph: 已编译的图（由路由层通过 get_graph 取到，惰性编译）
    :param identity: 当前用户身份（ID + 角色 + 范围），三个值都进运行时 config
    :param chat_dto: 本轮请求体
    :yield: SSE 帧字符串
    """
    # 先按最小配置查一次状态，拿到 checkpoint 后重建 config（_build_config 只认最终值）
    has_history, checkpoint_id = await _load_thread_state(
        graph,
        _build_config(identity, chat_dto.conversation_id, chat_dto.enable_web_search,
                      enabled_plugins=enabled_plugins),
    )

    config = _build_config(
        identity=identity,
        conversation_id=chat_dto.conversation_id,
        enable_web_search=chat_dto.enable_web_search,
        from_checkpoint_id=checkpoint_id,
        enabled_plugins=enabled_plugins,
    )

    messages: list = []
    if not has_history:
        # 首轮补一条 system 占位：context_build 会读 messages[0] 的 id 后整条覆盖渲染。
        # 内容留空即可，这一轮之前没有任何节点会展示它。
        messages.append(SystemMessage(content="", id=SYSTEM_MESSAGE_ID))
    messages.append(HumanMessage(content=chat_dto.content))

    async for frame in _stream_graph(graph, {"messages": messages}, config, "对话"):
        yield frame


async def resume_chat_service(
        graph: CompiledStateGraph[State],
        identity: Identity,
        resume_dto: ResumeDto,
        enabled_plugins: str = "",
) -> AsyncGenerator[str, None]:
    """
    中断恢复：把用户对工具调用的选择回传给图，从 interrupt 处继续跑。

    resume 的值必须与 approval_gate 里 interrupt 的读取方式一致：
    节点读 `user_choice["choice"]`，edit 分支还要读 `user_choice["edit"]`，
    所以两个键都要给，reject 时 edit 给空串即可（不会被读取）。

    不补 SystemMessage：线程里已有历史，补了就是第二条 system。
    也不传 from_checkpoint_id：resume 不产生新的用户消息，写不进去也不该写。

    :param graph: 已编译的图
    :param identity: 当前用户身份（ID + 角色 + 范围）
    :param resume_dto: 会话ID、选择、编辑内容、联网开关
    :yield: SSE 帧字符串
    :raises BusinessException: choice=edit 但没给编辑内容
    """
    if resume_dto.choice == "edit" and not (resume_dto.edit_content or "").strip():
        # 前端弹窗选"编辑"却没提交内容，属于非法请求；放过去节点里会拼出 "edit:" 空内容
        raise BusinessException(message="选择编辑时必须提供修改后的内容", code=400)

    config = _build_config(identity, resume_dto.conversation_id, resume_dto.enable_web_search,
                           enabled_plugins=enabled_plugins)

    graph_input = Command(resume={
        "choice": resume_dto.choice,
        "edit": resume_dto.edit_content or "",
    })

    async for frame in _stream_graph(graph, graph_input, config, "中断恢复"):
        yield frame


async def clear_conversation_memory_service(user_id: int, conversation_id: int) -> Result[None]:
    """
    删除一个会话的全部记忆（**不可恢复**）。

    四处都要清，漏一处就会出现「前端看不到但模型还记得」：
    1. PostgreSQL checkpoint：`adelete_thread` 删掉这条 thread 的所有快照；
    2. Milvus：drop 该会话专属集合，并清掉进程内的「已建表」缓存；
    3. MySQL chat_message：软删，保留归档但不再参与展示与检索；
    4. MySQL agent_task：软删，避免任务清单从已清空的记忆里"复活"。

    不删 conversation 行本身：删会话是另一个接口，两者刻意分开。

    跨存储不在同一个事务里，顺序上先删图状态、再删向量、最后删 MySQL：
    前两步失败会中途抛错（前端可重试），MySQL 放在最后能保证「图已经没了但消息还在」
    比反过来更容易恢复——消息还在，用户看得到，也知道发生了什么。

    :param user_id: 网关注入的用户ID
    :param conversation_id: 目标会话ID
    :return: 无数据的成功响应
    :raises BusinessException: 404 会话不存在或无权访问
    """
    await require_owned_conversation(user_id, conversation_id)

    # 1. checkpoint 线程（thread_id 与 conversation_id 同值）
    await get_saver().adelete_thread(str(conversation_id))

    # 2. 该会话的向量集合。drop 后必须清缓存，否则下次写入会以为集合还在、跳过建表
    dropped = await drop_session_memory_collection(user_id, conversation_id)

    # 3. 消息与任务：一条 UPDATE 批量软删，比逐行改省往返
    async with session_scope() as db:
        await db.execute(
            update(ChatMessageEntity)
            .where(
                ChatMessageEntity.conversation_id == conversation_id,
                ChatMessageEntity.deleted == 0,
            )
            .values(deleted=1)
        )
        await db.execute(
            update(AgentTaskEntity)
            .where(
                AgentTaskEntity.conversation_id == conversation_id,
                AgentTaskEntity.deleted == 0,
            )
            .values(deleted=1)
        )
        await db.commit()

    logger.info(
        "会话记忆已清除：user_id=%s conversation_id=%s milvus_dropped=%s",
        user_id, conversation_id, dropped,
    )
    return Result.success(message="会话记忆已清除")


async def backtrack_chat_service(
        graph: CompiledStateGraph[State],
        identity: Identity,
        backtrack_dto: BacktrackDto,
        enabled_plugins: str = "",
) -> AsyncGenerator[str, None]:
    """
    时间回溯：改写一条历史用户消息，并从它之前的状态重新生成。

    与「重新发一条新消息」的区别在于状态起点：这里用该消息的 from_checkpoint_id
    让图从历史快照分叉，而不是接着最新状态往下走，所以旧回复不会变成新回复的上下文。

    旧数据怎么处理（都只影响这条消息之后）：
    - chat_message：把该消息及其之后的全部软删。之所以不「就地改写内容」，
      是因为图入口的 save_human 会再插一条新行——就地改会变成一条旧行 + 一条新行重复。
    - agent_task：按时间清掉该消息之后的轮次产生的任务，否则任务清单会带着旧结论复活。
    - Milvus：按 create_time 删掉之后的切片，避免模型"记得"已被撤销的内容。

    首条消息没有父快照（from_checkpoint_id 为 NULL）时，等价于"从头开始"：
    清空线程后重新补一条 SystemMessage 占位。

    :param graph: 已编译的图
    :param identity: 当前用户身份（ID + 角色 + 范围）
    :param backtrack_dto: 会话ID、被改写的消息ID、新内容
    :yield: SSE 帧字符串
    :raises BusinessException: 404 消息不存在 / 400 消息不是用户消息
    """
    # 下面的清库、删向量、打日志只需要 ID；完整身份只传给 _build_config
    user_id = identity.user_id

    async with session_scope() as db:
        query = select(ChatMessageEntity).where(
            ChatMessageEntity.id == backtrack_dto.message_id,
            ChatMessageEntity.conversation_id == backtrack_dto.conversation_id,
            ChatMessageEntity.deleted == 0,
        )
        message = (await db.execute(query)).scalar_one_or_none()

        if message is None:
            raise BusinessException(message="消息不存在或已被删除", code=404)

        if message.role != "user":
            # 只允许改写用户自己的话。改 AI 消息等于让模型否认自己说过的话，语义不成立
            raise BusinessException(message="只能从用户消息处回溯", code=400)

        from_checkpoint_id = message.from_checkpoint_id
        message_time = message.create_time
        message_id = message.id

        # 这条消息之前还有没有别的用户消息。
        # 用来区分「首条消息」和「老数据缺快照」：两者 from_checkpoint_id 都是 NULL，
        # 但前者可以清空线程从头开始，后者清空会把整段历史一起丢掉。
        earlier_user_messages = (await db.execute(
            select(func.count()).select_from(ChatMessageEntity).where(
                ChatMessageEntity.conversation_id == backtrack_dto.conversation_id,
                ChatMessageEntity.role == "user",
                ChatMessageEntity.id < message_id,
                ChatMessageEntity.deleted == 0,
            )
        )).scalar_one()

    if not from_checkpoint_id and earlier_user_messages > 0:
        # 加列之前产生的历史消息没有快照可回，只能拒绝；静默清空线程会连带删掉前面所有轮次
        raise BusinessException(
            message="这条消息缺少历史快照，无法回溯；请从更早的消息处操作或重新开始会话",
            code=400,
        )

    # ---------- 1. 清掉这条消息及之后的 MySQL 数据 ----------
    async with session_scope() as db:
        await db.execute(
            update(ChatMessageEntity)
            .where(
                ChatMessageEntity.conversation_id == backtrack_dto.conversation_id,
                ChatMessageEntity.id >= message_id,
                ChatMessageEntity.deleted == 0,
            )
            .values(deleted=1)
        )
        if message_time is not None:
            # 任务没有 message_id，只能按时间圈定；同一秒内产生的任务会一起被清，
            # 这比"留着旧任务让清单复活"更安全
            await db.execute(
                update(AgentTaskEntity)
                .where(
                    AgentTaskEntity.conversation_id == backtrack_dto.conversation_id,
                    AgentTaskEntity.create_time >= message_time,
                    AgentTaskEntity.deleted == 0,
                )
                .values(deleted=1)
            )
        await db.commit()

    # ---------- 2. 清掉之后的向量切片 ----------
    if message_time is not None:
        await delete_session_memory_since(
            user_id=user_id,
            conversation_id=backtrack_dto.conversation_id,
            create_time_text=message_time.isoformat(sep=" "),
        )

    # ---------- 3. 从历史快照分叉，重新生成 ----------
    messages: list = []
    if from_checkpoint_id:
        config = _build_config(
            user_id=user_id,
            conversation_id=backtrack_dto.conversation_id,
            from_checkpoint_id=from_checkpoint_id,
            fork_checkpoint_id=from_checkpoint_id,
            enabled_plugins=enabled_plugins,
        )
    else:
        # 首条消息的回溯：没有可用快照，只能清空线程从头开始
        await get_saver().adelete_thread(str(backtrack_dto.conversation_id))
        config = _build_config(identity=identity, conversation_id=backtrack_dto.conversation_id,
                               enabled_plugins=enabled_plugins)
        messages.append(SystemMessage(content="", id=SYSTEM_MESSAGE_ID))

    messages.append(HumanMessage(content=backtrack_dto.content))

    logger.info(
        "时间回溯：user_id=%s conversation_id=%s from_message=%s fork=%s",
        user_id, backtrack_dto.conversation_id, message_id, bool(from_checkpoint_id),
    )

    async for frame in _stream_graph(graph, {"messages": messages}, config, "时间回溯"):
        yield frame
