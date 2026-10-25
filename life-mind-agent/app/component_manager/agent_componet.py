from typing import Tuple

from langchain_openai import ChatOpenAI

from app.agent.llm import create_chat_llm
from app.core.settings import settings


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
            chat_llm = create_chat_llm()
            cls._cache = {sig: chat_llm}

        return cls._cache[sig]