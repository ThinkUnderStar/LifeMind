from app.core.settings import settings
from langchain_openai import ChatOpenAI

#=========================用户交互 LLM===============================
def create_chat_llm() -> ChatOpenAI:
    chat_llm = ChatOpenAI(
        model=settings.CHAT_MODEL_NAME,
        temperature=settings.CHAT_MODEL_TEMPERATURE,
        base_url=settings.CHAT_MODEL_BASE_URL,
        api_key=settings.CHAT_MODEL_API_KEY,
        extra_body={
            "thinking": {"type": "disabled"}  #考虑到兼容问题，关闭深度思考
        }
    )
    return chat_llm