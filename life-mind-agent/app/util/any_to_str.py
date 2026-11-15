import logging

from langchain_core.documents import Document
from langgraph.store.base import SearchItem

from app.schemas.graph.state import TaskItem

logger = logging.getLogger(__name__)


def user_memories_to_str(
        items: list[SearchItem] | None,
        *,
        with_key: bool = True,
        max_chars: int | None = None,
) -> str:
    """
    把 store 的检索结果拼成一段可直接注入提示词的纯文本。

    条目之间空一行分隔；with_key 打开时每条前面加 "## {key}" 小标题，
    这样多维度存储（profile / health / preferences）拼起来后仍能看出各段来源。
    顺序沿用入参顺序：asearch 返回的已按 updated_at 倒序排好，这里不再重排。

    value 的约定是 {"data": "<文本>"}。若存的时候用了别的结构，退化为把 value
    当字符串用；两者都不成立时跳过着一条并记 warn，而不是抛异常 ——
    记忆文本缺失不该让整轮对话失败。

    :param items: store.asearch / 批量 aget 的返回，允许为 None 或空列表
    :param with_key: 是否给每条加 "## key" 小标题，单条场景可关掉
    :param max_chars: 拼接后允许的最大字符数，超出按整块取舍；None 表示不限制
    :return: 拼接后的文本，没有任何有效内容时返回空字符串
    """
    if not items:
        return ""

    blocks: list[str] = []
    for item in items:
        value = getattr(item, "value", None)
        text = value.get("data") if isinstance(value, dict) else value

        if not isinstance(text, str) or not text.strip():
            logger.warning("记忆条目内容为空或结构不符，已跳过：key=%s", item.key)
            continue

        text = text.strip()
        blocks.append(f"## {item.key}\n{text}" if with_key else text)

    if not blocks:
        return ""

    merged = "\n\n".join(blocks)

    # 画像会随对话轮数单调变长，这里给调用方一道硬上限，
    # 防止某次模型失控输出一大篇把提示词撑爆。
    # 按整块取舍而不是直接切字符串：直接切会切出「只剩 ## 标题没有正文」的残块
    if max_chars is not None and len(merged) > max_chars:
        kept: list[str] = []
        total = 0
        for block in blocks:
            extra = len(block) + (2 if kept else 0)
            if total + extra > max_chars:
                break
            kept.append(block)
            total += extra

        # 第一块自己就超限：没有整块可留，只能硬切，否则结果会是空串
        merged = "\n\n".join(kept) if kept else blocks[0][:max_chars]
        logger.warning("记忆文本超过 %s 字（实际 %s 字），已截断", max_chars, len("\n\n".join(blocks)))

    return merged


def session_memories_to_str(documents: list[Document] | None) -> str:
    """
    把「会话记忆集合」召回的片段拼成一段可直接注入提示词的纯文本。

    专用工具，不是通用的 List[Document] 转换器。唯一数据源是 Milvus 会话记忆集合
    （life_mind_session_memory_{user_id}_{conversation_id}），唯一调用方是 recall 节点，
    结果写进 State.relevant_session_memories，最终填 SYSTEM_PROMPT_TEMPLATE 的
    {session_recall}。以下几类结果都不要往这里传：

    - llm_memory_processor 切出来的块：带 metadata，写库必须用原始 Document；
    - embedding_docs 的入参：需要原始 Document 才能生成向量行；
    - retriever / reranker 的中间候选：还带着相似度分数，转成文本就没了；
    - 通用知识库检索结果，以及 PG store 的 SearchItem（后者另见 user_memories_to_str）。

    转换是有损且单向的：只取 page_content，丢掉 metadata、分数和来源；片段之间空一行，
    顺序沿用召回结果的相似度顺序（不重排）；不加小标题，也不限长 —— 长度由调用侧的
    RETRIEVE_FINAL_TOP_K 控制。空白片段跳过；page_content 不是字符串时记 warn 跳过，
    不让一条脏数据打断整轮对话。

    与 user_memories_to_str 的分工：那边处理 PG store 的 SearchItem（用户级长期记忆，
    带 "## key" 小标题、可用 max_chars 限长），这里处理会话记忆集合的 Document；
    两者分别对应模板的 {memories} 与 {session_recall}。

    输入契约：可迭代的 Document 集合（list / tuple / generator 均可），元素需有
    page_content: str。类型不符的元素只记 warn 并跳过，不抛异常；全都不合规时返回空串。

    :param documents: 会话记忆集合的召回结果，允许为 None 或空列表
    :return: 拼接后的文本，没有任何有效内容时返回空字符串
    """
    if not documents:
        return ""

    blocks: list[str] = []
    for doc in documents:
        text = getattr(doc, "page_content", None)
        if not isinstance(text, str):
            logger.warning("检索片段 page_content 不是字符串，已跳过：%r", type(text))
            continue

        content = text.strip()
        if content:
            blocks.append(content)

    return "\n\n".join(blocks)


def task_list_to_str(tasks: list[TaskItem] | None) -> str:
    """
    把 State.task_list（本会话的任务清单）拼成一段可直接注入提示词的纯文本。

    专用工具，不是通用的对象列表转字符串：唯一数据源是 State.task_list 里的
    TaskItem（字段同构的 AgentTaskEntity 也能用），典型调用方是 planner 节点 ——
    把「本会话已经有哪些任务、各自跑到哪一步、结果是什么」作为只读输入喂给模型，
    它才知道该重写哪一条、在末尾追加哪一条。以下几类结果都不要往这里传：

    - messages / task_messages：BaseMessage 列表，走各自的上下文装配；
    - List[Document] / SearchItem：另见 session_memories_to_str / user_memories_to_str。

    输出沿用清单顺序连续编号，不按 id 重排（与「按 id 升序展示」以及 PlannerOutput
    「完整清单整表覆盖」的顺序口径一致），被跳过的脏数据不占编号。每条先一行
    "序号. [状态] 目标"，result 有内容时再另起一行缩进写执行结果。
    状态只有 pending / running / done
    三值，done 只表示「本条执行已结束」、不代表成功，成败看结果文本；本函数
    不做任何判断，也不替调用方裁决任务是否完成。

    输入契约：元素需有 goal: str；status / result 可以缺，分别按 pending / 无处理。
    goal 不是字符串或为空白时记 warn 跳过，不让一条脏数据打断整轮对话；
    tasks 为 None / 空列表 / 全都不合规时返回空字符串，由调用方判断「暂无任务」。
    转换是有损且单向的，只用于提示词注入，不要拿这段文本还原 TaskItem 或落库。

    :param tasks: State.task_list，允许为 None 或空列表
    :return: 拼接后的文本，没有有效任务时返回空字符串
    """
    if not tasks:
        return ""

    lines: list[str] = []
    seq = 0
    for index, task in enumerate(tasks, start=1):
        goal = getattr(task, "goal", None)
        if not isinstance(goal, str) or not goal.strip():
            logger.warning("任务目标为空或不是字符串，已跳过：第 %s 条 %r", index, type(goal))
            continue

        status = getattr(task, "status", None)
        if not isinstance(status, str) or not status.strip():
            status = "pending"

        # 编号按「有效任务」连续递增：被跳过的脏数据不留空洞，
        # 否则模型看到 "4." 却只有一条任务，指代会错位。
        seq += 1
        lines.append(f"{seq}. [{status}] {goal.strip()}")

        result = getattr(task, "result", None)
        if isinstance(result, str) and result.strip():
            lines.append(f"   执行结果：{result.strip()}")

    return "\n".join(lines)
