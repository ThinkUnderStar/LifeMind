"""
服务插件目录：划分「agent 内置工具」与「服务插件工具」，并按用户启用的插件过滤工具集。

这个模块只做三件纯内存的事，不查库、不发请求：
1. 定义内置工具清单（无插件前缀，任何用户都有）；
2. 登记插件 key 到服务工具的关系（`todo` → `todo_create` / `todo_list` ...）；
3. 把请求头里的 `LifeMind-User-Plugins` 解析成集合，按集合挑出可用工具。

为什么不把映射写在 `agent_component` 里：
`run_tool` 执行前也要判一次「这个工具所属插件是否启用」，两处必须用同一份数据，
放在这里两边都 import 它，就不会出现「绑定时过滤了、执行时没过滤」的错位。

为什么不用工具名前缀直接切分：
前缀只能推导归属，表达不了「这个工具要展示成什么」以及「这个工具本来就不该给用户看」。
前缀留给启动自检用（发现登记漏写），运行时判断一律查这张表。

注意：本模块不 import 任何 agent 内部模块，只 import 工具对象本身，
避免与 `agent_component` / `nodes` 形成 import 环。
"""
from typing import Any, Dict, FrozenSet, List, Optional, Tuple

from langchain_core.tools import BaseTool

from app.agent.tools import recall_memory, save_user_memory, update_tasks, web_search

# 内置工具：agent 自己的能力，不属于任何插件，所有用户都可用。
# 它们不会出现在前端「插件服务卡」里，命令与工具名保持无前缀。
BUILTIN_TOOLS: Tuple[BaseTool, ...] = (
    save_user_memory,
    update_tasks,
    recall_memory,
    web_search,
)

# 服务插件工具：plugin_key → 该插件提供的工具。
#
# 现在为空：agent 侧还没有任何组件服务工具（日程 / 待办 / 账单 / 健康都还没接入）。
# 接入第一个服务时按下面的形式登记即可，工具名统一为「插件key_动作」：
#
#     # 实现在 app/agent/tools/service_tools.py，由 tools 包的 __init__ 统一导出
#     from app.agent.tools import todo_create, todo_list
#     SERVICE_TOOLS: Dict[str, Tuple[BaseTool, ...]] = {
#         "todo": (todo_create, todo_list),
#     }
#
# 登记后不需要改 `agent_component`：它会通过 `resolve_tools` 自动带上。
SERVICE_TOOLS: Dict[str, Tuple[BaseTool, ...]] = {}


def parse_plugins(raw: Optional[str]) -> FrozenSet[str]:
    """
    解析网关注入的插件标识串。

    网关已保证格式是逗号分隔的 plugin_key，这里只做防御性清洗：
    去空白、转小写、丢掉空段。解析不出任何东西时返回空集合，
    调用方据此只绑定内置工具（fail-closed）。

    Args:
        raw: 请求头 `LifeMind-User-Plugins` 的原值，可能为 None

    Returns:
        FrozenSet[str]: 插件标识集合，可直接作为缓存 key 的一部分
    """
    if not raw:
        return frozenset()

    return frozenset(
        part.strip().lower()
        for part in raw.split(",")
        if part and part.strip()
    )


def is_plugin_enabled(plugin_key: str, enabled_plugins: Any) -> bool:
    """
    判断某个插件是否对该用户启用。

    两种入参都支持：已经解析好的集合，或原始逗号串。
    这样 `run_tool` 里可以直接传 config 里的原值，不必先转换一次。

    Args:
        plugin_key: 插件标识，如 todo
        enabled_plugins: 插件集合，或逗号分隔的原始串

    Returns:
        bool: 是否启用；插件标识为空或入参为空时返回 False
    """
    if not plugin_key:
        return False

    if isinstance(enabled_plugins, str) or enabled_plugins is None:
        return plugin_key.strip().lower() in parse_plugins(enabled_plugins)

    return plugin_key.strip().lower() in enabled_plugins


def resolve_tools(enabled_plugins: Any) -> List[BaseTool]:
    """
    组装本次请求可用的工具集：内置工具 + 已启用插件的服务工具。

    顺序固定为「内置在前、插件按登记顺序在后」：
    bind_tools 的列表顺序会影响模型看到的工具顺序，固定下来便于复现问题。

    Args:
        enabled_plugins: 插件集合，或逗号分隔的原始串

    Returns:
        List[BaseTool]: 可直接传给 bind_tools 的工具列表
    """
    plugins = (
        parse_plugins(enabled_plugins)
        if isinstance(enabled_plugins, str) or enabled_plugins is None
        else frozenset(enabled_plugins)
    )

    tools: List[BaseTool] = list(BUILTIN_TOOLS)

    for plugin_key, plugin_tools in SERVICE_TOOLS.items():
        if plugin_key in plugins:
            tools.extend(plugin_tools)

    return tools


def tools_fingerprint(enabled_plugins: Any) -> Tuple[str, ...]:
    """
    生成工具集的短指纹，用作模型实例缓存的 key 的一部分。

    只取「已启用的插件 key」并按字典序排序：同一组插件不管请求头里
    顺序如何，都应该命中同一个缓存条目。

    Args:
        enabled_plugins: 插件集合，或逗号分隔的原始串

    Returns:
        Tuple[str, ...]: 排序后的插件标识元组，可直接放进 dict 的 key
    """
    plugins = (
        parse_plugins(enabled_plugins)
        if isinstance(enabled_plugins, str) or enabled_plugins is None
        else frozenset(enabled_plugins)
    )
    return tuple(sorted(plugins))
