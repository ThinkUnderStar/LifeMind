from typing import Tuple

from langchain_openai import ChatOpenAI

from app.agent.llm import create_chat_llm
from app.core.settings import settings


class chat_llm_manager:
    _cache: dict[Tuple[str, str, str, float], ChatOpenAI] = {}

    @classmethod
    def get_chat_llm(cls) -> ChatOpenAI:
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