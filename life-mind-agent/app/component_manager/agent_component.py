"""
聊天大模型组件。

把「怎么造一个 ChatOpenAI 实例」和「造好的实例怎么复用」收在同一处：
前者是下面的 _create_chat_llm，后者是 ChatLlmManager 的签名缓存。
调用方一律通过 ChatLlmManager.get_chat_llm() 取实例，不要自己 new。
"""
from typing import Tuple

from langchain_openai import ChatOpenAI

from app.core.settings import settings


def _create_chat_llm() -> ChatOpenAI:
    """
    按当前配置创建一个新的大模型客户端实例。

    只负责"创建"，不负责缓存：每次调用都返回全新实例。
    复用逻辑交给 ChatLlmManager，两者职责分离，缓存失效时好定位。

    Returns:
        ChatOpenAI: 按 settings 中 CHAT_MODEL_* 配置构造的客户端。
    """
    return ChatOpenAI(
        model=settings.CHAT_MODEL_NAME,
        temperature=settings.CHAT_MODEL_TEMPERATURE,
        base_url=settings.CHAT_MODEL_BASE_URL,
        api_key=settings.CHAT_MODEL_API_KEY,
        extra_body={
            # 兼容性考虑：部分中转 / 兼容接口不认 thinking 参数，
            # 显式关闭深度思考，避免请求被上游拒绝
            "thinking": {"type": "disabled"}
        }
    )


class ChatLlmManager:
    """
    聊天大模型实例管理器。

    核心机制：懒加载（Lazy Loading）+ 签名缓存（Signature Cache）。
    用于支持 Nacos 动态配置热更新，确保每次获取的都是当前最新配置对应的实例。
    """
    _cache: dict[Tuple[str, str, str, float], ChatOpenAI] = {}

    @classmethod
    def get_chat_llm(cls) -> ChatOpenAI:
        """
        获取当前配置对应的大模型实例。

        如果当前配置的实例已存在，直接复用；如果配置发生变化（如 Nacos 热更新），
        则会自动创建新实例并替换旧缓存。
        """
        sig = (
            settings.CHAT_MODEL_NAME,
            settings.CHAT_MODEL_TEMPERATURE,
            settings.CHAT_MODEL_BASE_URL,
            settings.CHAT_MODEL_API_KEY,
        )

        if sig not in cls._cache:
            chat_llm = _create_chat_llm()
            cls._cache = {sig: chat_llm}

        return cls._cache[sig]
