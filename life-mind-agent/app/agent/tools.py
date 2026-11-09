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

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from app.db.postgresql.session import get_store

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
