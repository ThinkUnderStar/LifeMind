"""
Agent 组件：聊天大模型。

同一个模型有两种形态，各有一个管理器：
1. ChatLlmManager          —— 纯对话模型，普通问答用；
2. ChatLlmWithToolsManager —— 绑定了工具集的模型，LangGraph 的 llm_node 用。

「怎么造实例」在下面的 _create_chat_llm，「造好的实例怎么复用」在各自的签名缓存里。
调用方一律走 Manager 取实例，不要自己 new。
"""
from typing import Tuple

from langchain_core.runnables import Runnable
from langchain_ollama import ChatOllama
from langchain_openai import ChatOpenAI

from app.agent.tools import save_user_memory
from app.core.settings import settings
from app.schemas.graph.output import GateOutput


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


class ChatLlmWithToolsManager:
    """
    带工具的聊天模型管理器。

    供 LangGraph 的 llm_node 使用：模型绑定工具后，一次调用可能不直接给文本，
    而是返回 tool_calls，由节点决定是去执行工具还是继续往下走。

    核心机制与 ChatLlmManager 一致（懒加载 + 签名缓存），
    区别只是这里的实例多了一层 bind_tools。
    """

    # 缓存：{ (模型名, 温度, base_url, api_key): 已绑定工具的模型 }
    _cache: dict[Tuple[str, str, str, float], Runnable] = {}

    @classmethod
    def get_chat_llm_with_tools(cls) -> Runnable:
        """
        获取绑定了工具集的模型实例。

        注意：工具列表是在这里 bind 进 Runnable 的，属于实例的一部分；
        而签名只取自模型配置、不含工具列表，所以增删工具不会触发重建，
        改完工具集需要重启或清缓存才生效。

        Returns:
            Runnable: 已绑定工具的模型，支持 astream / ainvoke，输出里可能带 tool_calls。
        """
        # 签名与 ChatLlmManager 保持一致，同样是四个模型配置项
        sig = (
            settings.CHAT_MODEL_NAME,
            settings.CHAT_MODEL_TEMPERATURE,
            settings.CHAT_MODEL_BASE_URL,
            settings.CHAT_MODEL_API_KEY,
        )

        if sig not in cls._cache:
            # 先取底层模型再绑工具：两层缓存各自独立，热更新时一起失效
            chat_llm = ChatLlmManager.get_chat_llm()
            # 工具集暂为空，待注册；bind 阶段不报错，但请求时 tools=[] 会被多数接口拒绝
            chat_llm_with_tools = chat_llm.bind_tools([
                # 保存为用户级记忆
                save_user_memory,
            ])
            # 整体替换而非新增，缓存只留当前配置一份
            cls._cache = {sig: chat_llm_with_tools}

        return cls._cache[sig]

class GateLlmManager:
    _cache: dict[str, Runnable] = {}

    @classmethod
    def get_gate_llm(cls) -> Runnable:

        sig = settings.GATE_MODEL_NAME

        if sig not in cls._cache:
            gate_llm = ChatOllama(
                model=settings.GATE_MODEL_NAME,
                temperature=0
            )
            # 模型结构化输出
            gate_llm_structured = gate_llm.with_structured_output(schema=GateOutput)

            cls._cache = {sig: gate_llm_structured}

        return cls._cache[sig]

