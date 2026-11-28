"""
Agent 组件：聊天大模型。

同一个模型有两种形态，各有一个管理器：
1. ChatLlmManager          —— 纯对话模型，普通问答用；
2. ChatLlmWithToolsManager —— 绑定了工具集的模型，LangGraph 的 llm_node 用。

「怎么造实例」在下面的 _create_chat_llm，「造好的实例怎么复用」在各自的签名缓存里。
调用方一律走 Manager 取实例，不要自己 new。
"""
from typing import Any, Optional, Tuple

from langchain_core.runnables import Runnable
from langchain_ollama import ChatOllama
from langchain_openai import ChatOpenAI

from app.agent.service_catalog import resolve_tools, tools_fingerprint
from app.core.settings import settings
from app.schemas.graph.output import GateOutput, PlannerOutput


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
    区别是这里有两层签名：
    - 配置签名（模型名 / 温度 / base_url / api_key）：变了就整体清空缓存，
      保证 Nacos 热更新后不会继续用旧模型；
    - 工具签名（用户启用的插件集合）：不同用户启用不同插件，工具集不同，
      所以同一个模型配置下会有多份 bind 结果，按插件集合分别缓存。
    """

    # 缓存：{ 配置签名 + 插件指纹: 已绑定工具的模型 }
    _cache: dict[Tuple[str, str, str, float, Tuple[str, ...]], Runnable] = {}

    # 上一次使用的模型配置签名：变化时整体清空缓存
    _config_sig: Optional[Tuple[str, str, str, float]] = None

    # 缓存条目上限：插件组合数是 2^n（n 为插件种数，个位数），
    # 正常远达不到这个数；设上限只为防止脏数据（请求头里塞几百个 key）把内存撑爆
    _MAX_CACHE_SIZE = 32

    @classmethod
    def get_chat_llm_with_tools(cls, enabled_plugins: Any = "") -> Runnable:
        """
        获取绑定了工具集的模型实例。

        Args:
            enabled_plugins: 用户启用的插件集合，或 `LifeMind-User-Plugins` 原始串。
                网关注入、服务层透传；缺失时为空，此时只绑定内置工具（fail-closed）。

        Returns:
            Runnable: 已绑定工具的模型，支持 astream / ainvoke，输出里可能带 tool_calls。
        """
        # 配置签名只取模型四项：与 ChatLlmManager 保持一致
        config_sig = (
            settings.CHAT_MODEL_NAME,
            settings.CHAT_MODEL_TEMPERATURE,
            settings.CHAT_MODEL_BASE_URL,
            settings.CHAT_MODEL_API_KEY,
        )

        # 配置变了：旧实例全部作废，连同按插件分出来的多份 bind 结果一起清掉
        if cls._config_sig != config_sig:
            cls._cache.clear()
            cls._config_sig = config_sig

        # 工具签名：同一组插件不管请求头里顺序如何，都命中同一条缓存
        sig = config_sig + (tools_fingerprint(enabled_plugins),)

        if sig not in cls._cache:
            if len(cls._cache) >= cls._MAX_CACHE_SIZE:
                cls._cache.clear()

            # 先取底层模型再绑工具：两层缓存各自独立，配置热更新时一起失效
            chat_llm = ChatLlmManager.get_chat_llm()

            # 工具集 = 内置工具 + 已启用插件的服务工具；未启用的服务工具不会出现在这里，
            # 模型看不到它就不会去调用，这比"调用了再拒绝"少一轮无效往返
            cls._cache[sig] = chat_llm.bind_tools(resolve_tools(enabled_plugins))

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

class PlannerLlmManager:
    _cache: dict[str, Runnable] = {}

    @classmethod
    def get_planner_llm(cls) -> Runnable:
        sig = settings.PLANNER_MODEL_NAME

        if sig not in cls._cache:
            planner_llm = ChatOllama(
                model=settings.PLANNER_MODEL_NAME,
                temperature=0.2,
                # deepseek-r1 默认开 thinking：哪怕只输出一份任务清单，也要先写 1300+ 字推理，
                # 实测 24s vs 关掉后 0.7s。规划是结构化抽取，不需要推理，显式关掉。
                reasoning=False,
            )
            # 模型结构化输出
            planner_llm_structured = planner_llm.with_structured_output(schema=PlannerOutput)

            cls._cache = {sig: planner_llm_structured}

        return cls._cache[sig]