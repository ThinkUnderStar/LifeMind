"""
Agent 可调用的工具集合。

与 nodes.py 的区别：节点由图编排、按固定顺序执行；工具由模型决定何时调用、传什么参数，
所以每个工具的 docstring 都要写清「什么时候该用」，模型靠这段描述判断，而不是靠代码。

与 relevant_user_memories 节点共用同一个命名空间与 value 约定（value 形如 {"data": "<文本>"}），
两边不一致的表现是「写进去了但注入不到」，且不会报错——常量定义在这里，节点侧应从这里引用。

一条记忆 = store 里的一个条目，key 是「uuid_时间（精确到分）」：
uuid 保证并发生成时不撞 key，时间部分让人能直接看出这条是什么时候记的、也便于人工排查。
"""
import logging
import uuid
from datetime import datetime
from typing import Annotated, List

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import InjectedToolArg, tool

from app.core.settings import settings
from app.component_manager.tavily_component import TavilyManager
from app.db.mysql.entities import AgentTaskEntity
from app.db.mysql.session import session_scope
from app.db.postgresql.session import get_store
from app.rag import rag_flow
from app.schemas.graph.state import TaskItem, State
from app.util.any_to_str import session_memories_to_str, format_tavily_response

logger = logging.getLogger(__name__)

# 用户级记忆在 store 里的命名空间首段，第二段是 user_id。
USER_MEMORY_NAMESPACE = "users_memory"

# key 里的时间格式，精确到分。同一分钟里靠 uuid 区分，不靠秒：
# 秒级会让 key 长一截，而「哪分钟记的」对排查已经够用。
USER_MEMORY_TIME_FORMAT = "%Y%m%d%H%M"

# 单条记忆的长度上限。一条只记一件事，超了说明模型把整段对话塞进来了，截断并留痕。
USER_MEMORY_ITEM_MAX_CHARS = 500

# 每个用户的记忆条目上限。条目随对话轮数累积，注入时是全量读取，
# 不设上限会让提示词无限膨胀。超限后从最旧的开始删，保住最近的记忆。
USER_MEMORY_MAX_ITEMS = 200


@tool(name_or_callable="save_user_memory")
async def save_user_memory(content: str, config: RunnableConfig) -> str:
    """
    把关于用户的一条长期信息写入用户级记忆，跨会话长期生效。

    什么时候该用：用户说出了自己的**稳定属性或长期状况**——身份、职业、家庭关系、健康与睡眠、
    长期目标、明确的偏好与禁忌。这类信息下一轮对话仍然成立，值得记住。

    什么时候不该用：
    - 一次性的寒暄、情绪发泄、当下的临时状态（“我今天有点累”），过几轮就不成立了；
    - 用户明确表示不要记录的隐私；
    - 上面「关于用户的长期记忆」里已经写过的信息——不要重复保存，也不要为了确认而调用本工具。

    一条信息存成一个独立条目，已有条目不会被覆盖。

    :param content: 要记住的一条事实，写成一句完整、脱离上下文也看得懂的话，
                   例如“长期失眠，凌晨两三点才睡”；不要写“他说他睡不着”这种带指代和转述的句子
    """
    configurable = config.get("configurable", {})
    user_id = configurable.get("user_id")
    if user_id is None or user_id == "":
        # 身份只认 config（网关注入的头 → config），没有就放弃，绝不落到兜底空间污染别人的记忆
        logger.error("保存用户记忆缺少 user_id，已跳过：configurable=%s", configurable)
        return "未取到当前用户身份，这条信息没有保存。"

    fact = content.strip()
    if not fact:
        return "内容为空，没有保存。"

    if len(fact) > USER_MEMORY_ITEM_MAX_CHARS:
        logger.warning("单条记忆超过 %s 字，已截断：user_id=%s 原长=%s",
                       USER_MEMORY_ITEM_MAX_CHARS, user_id, len(fact))
        fact = fact[:USER_MEMORY_ITEM_MAX_CHARS]

    namespace = (USER_MEMORY_NAMESPACE, str(user_id))

    try:
        store = get_store()
        # key 是 uuid_时间（时间精确到分）：uuid 保证并发生成时不撞 key，
        # 时间部分让人一眼看出这条是什么时候记的
        key = f"{uuid.uuid4().hex}_{datetime.now().strftime(USER_MEMORY_TIME_FORMAT)}"
        await store.aput(namespace, key, {"data": fact})

        # 条目数控制：不带 query 的 asearch 按 updated_at 倒序返回，末尾是最旧的。
        # 重复判断不放在工具里——模型每轮都拿得到完整的长期记忆，由它在提示词约束下
        # 自己决定该不该存，比在这里逐条字符串比对更准（模型能判断「换了工作」这类语义变化）。
        items = await store.asearch(namespace, limit=USER_MEMORY_MAX_ITEMS)
        if len(items) > USER_MEMORY_MAX_ITEMS:
            for outdated in items[USER_MEMORY_MAX_ITEMS:]:
                await store.adelete(namespace, outdated.key)
            logger.warning("用户记忆条目超过 %s 条，已删除最旧的 %s 条：user_id=%s",
                           USER_MEMORY_MAX_ITEMS, len(items) - USER_MEMORY_MAX_ITEMS, user_id)
    except Exception:
        # 记忆写失败不该让这一轮回答失败：模型已经生成的内容照常返回，只记日志
        logger.error("用户记忆写入失败：user_id=%s content_len=%s", user_id, len(fact), exc_info=True)
        return "这条信息保存失败，请稍后再试或忽略本条。"

    logger.info("用户记忆已保存：user_id=%s key=%s content_len=%s", user_id, key, len(fact))
    return f"已记住：{fact}"

@tool(name_or_callable="update_tasks")
async def update_tasks(
        state: Annotated[State, InjectedToolArg()],
        config: RunnableConfig,
        updated_tasks: List[TaskItem],
) -> List[TaskItem]:
    """
    新增任务，或按 id 改一条还没执行（pending）的任务目标。

    什么时候该用：执行中发现清单缺一步就新增；某条尚未运行的任务目标要改写，就传它的 id。
    正在执行（running）或已完成（done）的任务改不了——即便传了 id 也不会生效。

    :param updated_tasks: 要变更的任务列表；不带 id = 新增，带 id = 改那条的目标文本
    """
    configurable = config.get("configurable", {})
    conversation_id = configurable.get("conversation_id") or configurable.get("thread_id")
    tasks = state.task_list
    task_bind_id = state.task_bind_id
    async with session_scope() as db:
        for updated_task in updated_tasks:
            if not updated_task.id:
                new_task = AgentTaskEntity(
                    conversation_id=conversation_id,
                    task_bind_id=task_bind_id,
                    goal=updated_task.goal,
                    status="pending",
                )
                db.add(new_task)
                await db.commit()
                await db.refresh(new_task)

                # ORM 实体只留在 db 会话里；额外创建纯数据对象放进 State 的任务清单
                tasks.append(TaskItem(
                    id=new_task.id,
                    conversation_id=conversation_id,
                    task_bind_id=task_bind_id,
                    goal=updated_task.goal,
                    status="pending",
                ))

            else:
                # 有 id = 修改已有任务：只有还没执行（pending）的能改。
                # 状态以 State 任务清单里的真实值为准，不信模型传来的 updated_task.status；
                # 正在执行（running）或已完成（done）的一律跳过——不改库、也不改任务清单。
                old_task = next((t for t in tasks if t.id == updated_task.id), None)
                if old_task is None:
                    logger.warning("修改任务时任务清单里没有 id=%s 的任务，已跳过", updated_task.id)
                    continue
                if old_task.status != "pending":
                    logger.warning("任务 id=%s 状态为 %s（非 pending），不允许修改，已跳过",
                                   updated_task.id, old_task.status)
                    continue

                # 先查库里是否真有这一行，再改目标文本并提交；查不到就跳过，避免改出一个孤儿状态
                db_task = await db.get(AgentTaskEntity, updated_task.id)
                if db_task is None:
                    logger.error("数据库中没有 id=%s 的任务，已跳过修改", updated_task.id)
                    continue
                db_task.goal = updated_task.goal
                await db.commit()

                # 原地更新任务清单里那条：只重写目标文本，状态仍是 pending、id 与 result 不变
                old_task.goal = updated_task.goal

    # 返回最新任务清单：新增的已 append、修改的已原地更新，其余保持原样
    return tasks


@tool(name_or_callable="recall_memory")
async def recall_memory(query: str, config: RunnableConfig) -> str:
    """
    回忆本会话之前聊过的具体内容（用户说过的事、之前查到的结论、做过的决定）。

    什么时候该用：当前任务需要用到本会话早些时候的细节，而这些细节不在任务清单、
    也不在你当前看到的消息里——比如「之前算的那笔账是多少」「用户上条说的地址」。
    这是主动检索：query 由你根据当下需要自己写，而不是被动等系统注入的回忆。

    什么时候不该用：
    - 用户问的是跨会话的长期信息（身份、偏好、长期状况）——那是长期记忆，不是本会话回忆；
    - 单纯闲聊、或答案已经在你眼前的上下文里，不必检索。

    只能检索【当前这个会话】的记录，取不到别的会话或别的用户的任何内容。

    :param query: 要检索的内容，用一句完整的话描述你想回忆什么，例如“用户之前提到的过敏药”；
                  不要写用户身份或会话编号，工具会从运行时上下文自动取
    """
    configurable = config.get("configurable", {})
    user_id = configurable.get("user_id")
    conversation_id = configurable.get("conversation_id") or configurable.get("thread_id")
    if user_id is None or conversation_id is None:
        logger.error("回忆工具缺少 user_id/conversation_id，跳过：configurable=%s", configurable)
        return "未取到当前会话信息，无法回忆。"

    # 集合名规则必须与 nodes.py::_session_collection_name 逐字一致，否则读不到写入侧的行。
    # 不能在这里 import nodes 的那个函数：nodes 已经 import 本模块，反向引用会循环导入。
    try:
        collection_name = f"{settings.SESSION_MEMORY_COLLECTION}_{int(user_id)}_{int(conversation_id)}"
    except (TypeError, ValueError):
        logger.error("回忆工具的 user_id/conversation_id 不是整数：%r / %r", user_id, conversation_id)
        return "会话信息格式异常，无法回忆。"

    try:
        question = query.strip()
        if not question:
            return "检索内容为空，没有进行回忆。"

        documents = await rag_flow.rag_search(question=question, collection_name=collection_name)
        text = session_memories_to_str(documents)
    except Exception:
        # 检索失败不该让整轮任务失败：返回可读提示，由模型决定怎么继续
        logger.error("会话回忆检索失败：collection=%s query_len=%s", collection_name, len(query), exc_info=True)
        return "回忆时出错了，暂时取不到这部分内容。"

    if not text:
        return "本次会话里没有找到相关内容。"

    logger.info("回忆工具命中：collection=%s query_len=%s result_len=%s",
                collection_name, len(question), len(text))
    return text


@tool(name_or_callable="web_search")
async def web_search(query: str, config: RunnableConfig) -> str:
    """
    通过 Tavily 实时联网搜索，获取最新、准确的信息。

    什么时候该用：用户问的是时效性强、超出模型知识截止日期、需要外部事实核验的内容——
    最新新闻与事件、实时数据、近期发布的资料、或小众外部知识。模型靠自己记忆答不准时才调用。

    什么时候不该用：闲聊、常识、纯情绪表达；答案已经在本会话上下文或长期记忆里；
    用户明确说过不要联网的内容。

    搜索结果经过提炼，每条含标题、摘要和来源链接，并可能带 Tavily 生成的直接答案。
    能否使用由运行时开关决定：未开启时调用不会真正联网，只返回提示。

    :param query: 简洁、核心的搜索关键词或问题，不要写冗长的自然语言；
                  例如传 "LangGraph 1.6 新特性" 而不是 "你能告诉我 LangGraph 最近有哪些更新吗"
    """
    try:
        # 开关只在 config.configurable 里认：默认 0（关闭），必须显式给 1 才联网。
        # 模型只能传 query，改不了这个开关——它从运行时上下文取，防止模型绕过限制。
        enable = config.get("configurable", {}).get("enable_web_search", 0)
        if enable != 1:
            return "用户未开启联网搜索功能。"

        client = TavilyManager.get_client()
        response = await client.search(
            query=query,
            search_depth="advanced",
            max_results=5,
            include_answer=True,
            topic="general",
        )
        return format_tavily_response(response)
    except ImportError:
        logger.error("联网搜索失败：未安装 tavily-python 依赖")
        return "联网搜索暂不可用：服务端缺少依赖。"
    except ValueError:
        # TAVILY_API_KEY 未配置（Nacos 还没注入 / 漏配）
        logger.error("联网搜索失败：TAVILY_API_KEY 未配置")
        return "联网搜索暂不可用：未配置密钥。"
    except Exception:
        logger.error("联网搜索失败：query_len=%s", len(query), exc_info=True)
        return "联网搜索出错了，暂时取不到结果。"
