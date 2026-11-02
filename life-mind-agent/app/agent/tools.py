"""
Agent 可调用的工具集合。

与 nodes.py 的区别：节点由图编排、按固定顺序执行；工具由模型决定何时调用、传什么参数，
所以每个工具的 docstring 都要写清「什么时候该用」，模型靠这段描述判断，而不是靠代码。

与 relevant_user_memories 节点共用同一个命名空间与 key 约定（value 形如 {"data": "<文本>"}），
两边不一致的表现是「写进去了但注入不到」，且不会报错——常量定义在这里，节点侧应从这里引用。
"""
import logging

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from app.db.postgresql.session import get_store

logger = logging.getLogger(__name__)

# 用户级记忆在 store 里的命名空间首段，第二段是 user_id。
USER_MEMORY_NAMESPACE = "users_memory"

# 画像单 key：一个用户一份画像，内容靠换行分段。
# 不按维度拆多个 key，是为了让注入时只有一段文本，也不需要额外一次模型调用去判断维度。
USER_MEMORY_PROFILE_KEY = "profile"

# 画像长度上限。追加式写入会随对话轮数无限膨胀，超限后从最早的内容开始丢弃，
# 保证「最新说的」一定还在（与 store 检索按 updated_at 倒序的取舍方向一致）。
USER_MEMORY_MAX_CHARS = 4000
#
#
# @tool
# async def save_user_memory(content: str, config: RunnableConfig) -> str:
#     """
#     把关于用户的一条长期信息写入用户级记忆，跨会话长期生效。
#
#     什么时候该用：用户说出了自己的**稳定属性或长期状况**——身份、职业、家庭关系、健康与睡眠、
#     长期目标、明确的偏好与禁忌。这类信息下一轮对话仍然成立，值得记住。
#
#     什么时候不该用：
#     - 一次性的寒暄、情绪发泄、当下的临时状态（“我今天有点累”），过几轮就不成立了；
#     - 用户明确表示不要记录的隐私；
#     - 已经在记忆里的信息（重复保存会返回提示，不要反复调用）。
#
#     写入是追加式的：同一条内容不会重复写，旧内容不会被覆盖掉。
#
#     :param content: 要记住的一条事实，写成一句完整、脱离上下文也看得懂的话，
#                    例如“长期失眠，凌晨两三点才睡”；不要写“他说他睡不着”这种带指代和转述的句子
#     """
#     configurable = config.get("configurable", {})
#     user_id = configurable.get("user_id")
#     if user_id is None or user_id == "":
#         # 身份只认 config（网关注入的头 → config），没有就放弃，绝不落到兜底空间污染别人的记忆
#         logger.error("保存用户记忆缺少 user_id，已跳过：configurable=%s", configurable)
#         return "未取到当前用户身份，这条信息没有保存。"
#
#     fact = content.strip()
#     if not fact:
#         return "内容为空，没有保存。"
#
#     namespace = (USER_MEMORY_NAMESPACE, str(user_id))
#
#     try:
#         store = get_store()
#         existing = await store.aget(namespace, USER_MEMORY_PROFILE_KEY)
#         current = existing.value.get("data", "") if existing else ""
#         current = current if isinstance(current, str) else ""
#
#         if fact in current:
#             return "这条信息已经在长期记忆里了，无需重复保存。"
#
#         merged = f"{current}\n{fact}".strip() if current else fact
#         if len(merged) > USER_MEMORY_MAX_CHARS:
#             # 丢最早的内容而不是最新：模型刚说的这条优先级最高
#             merged = merged[-USER_MEMORY_MAX_CHARS:]
#             logger.warning("用户画像超过 %s 字，已丢弃最早的内容：user_id=%s",
#                            USER_MEMORY_MAX_CHARS, user_id)
#
#         await store.aput(namespace, USER_MEMORY_PROFILE_KEY, {"data": merged})
#     except Exception:
#         # 记忆写失败不该让这一轮回答失败：模型已经生成的内容照常返回，只记日志
#         logger.error("用户记忆写入失败：user_id=%s content_len=%s", user_id, len(fact), exc_info=True)
#         return "这条信息保存失败，请稍后再试或忽略本条。"
#
#     logger.info("用户记忆已保存：user_id=%s content_len=%s 画像总长=%s", user_id, len(fact), len(merged))
#     return f"已记住：{fact}"
