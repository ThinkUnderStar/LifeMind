"""
Tavily 联网搜索客户端管理。

与 rag_component 各 Manager、ChatLlmManager 保持一致：懒加载 + 签名缓存。
有两点必须在构造时机上守住，否则会踩坑：

1. 不能像别的项目那样在模块顶层 `from tavily import AsyncTavilyClient` 并直接 new：
   - 依赖可能还没装（用户手动装），顶层 import 会让整个 agent_tools.py / graph.py 起不来；
   - TAVILY_API_KEY 由 Nacos 在 lifespan 启动时才覆盖进 settings，模块导入时还是空串，
     顶层构造会拿到一个空 key 的客户端。
   所以 import 和构造都推迟到第一次真正调用 get_client() 时。

2. 签名是 TAVILY_API_KEY：key 被 Nacos 热更新后签名变化，下次获取自动重建客户端；
   key 不变则复用同一实例，避免每次搜索都 new 一个。

未装 tavily 或 key 为空时抛异常，交给调用它的工具捕获并降级成可读提示，
不让一次联网搜索失败打断整轮任务。
"""
from typing import Any, Dict

from app.core.settings import settings


class TavilyManager:
    # 缓存：{ api_key: AsyncTavilyClient 实例 }
    _cache: Dict[str, Any] = {}

    @classmethod
    def get_client(cls) -> Any:
        """
        获取当前配置对应的 Tavily 异步客户端。

        签名只有 TAVILY_API_KEY：换 key 就重建客户端。
        import 与构造都在这里做，确保拿到的是 Nacos 覆盖后的 key。

        :return: tavily.AsyncTavilyClient 实例
        :raises ImportError: 未安装 tavily-python 依赖
        :raises ValueError: TAVILY_API_KEY 未配置（Nacos 未注入）
        """
        sig = settings.TAVILY_API_KEY
        if not sig:
            raise ValueError("TAVILY_API_KEY 未配置")

        if sig not in cls._cache:
            # 延迟导入：没装依赖时不影响本模块被 import，只有真要搜索才报错
            from tavily import AsyncTavilyClient
            cls._cache = {sig: AsyncTavilyClient(api_key=sig)}

        return cls._cache[sig]
