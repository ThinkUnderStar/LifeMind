"""
服务插件目录：划分「agent 内置工具」与「服务插件工具」，并按用户启用的插件过滤工具集。

这个模块只做四件纯内存的事，不查库、不发请求：
1. 定义内置工具清单（无插件前缀，任何用户都有）；
2. 登记插件 key 到服务工具的关系（`schedule` → 日程那一批工具）；
3. 把请求头里的 `LifeMind-User-Plugins` 解析成集合，按集合挑出可用工具；
4. 声明「工具执行时前端该打开哪个面板」，供流式层推 ui 事件。

第 4 件事必须放在这里：面板映射依赖工具名，而工具名只在本模块汇总。
放到 chat_service 会变成第二份工具清单，加一个工具时漏改不会报错，
只会让那个工具执行完前端一片空白。

为什么不把映射写在 `agent_component` 里：
`run_tool` 执行前也要判一次「这个工具所属插件是否启用」，两处必须用同一份数据，
放在这里两边都 import 它，就不会出现「绑定时过滤了、执行时没过滤」的错位。

为什么不用工具名前缀直接切分：
前缀只能推导归属，表达不了「这个工具要展示成什么」以及「这个工具本来就不该给用户看」。
前缀留给启动自检用（发现登记漏写），运行时判断一律查这张表。

注意：本模块不 import 任何 agent 内部模块，只 import 工具对象本身，
避免与 `agent_component` / `nodes` 形成 import 环。
"""
import re
from typing import Any, Dict, FrozenSet, List, Optional, Tuple

from langchain_core.tools import BaseTool

from app.agent.tools import recall_memory, save_user_memory, update_tasks, web_search
from app.agent.tools.service_tools import READONLY_TOOL_NAMES, SCHEDULE_TOOLS

# 内置工具：agent 自己的能力，不属于任何插件，所有用户都可用。
# 它们不会出现在前端「插件服务卡」里，命令与工具名保持无前缀。
BUILTIN_TOOLS: Tuple[BaseTool, ...] = (
    save_user_memory,
    update_tasks,
    recall_memory,
    web_search,
)

# 内置工具里需要人工确认的。只列这一条：其余三个都是只读或纯内部状态操作。
# 单独放一个常量而不是和插件工具混在一张表里，是为了让「内置的敏感面」一眼可见——
# 以后加内置工具时，看这里就知道要不要登记。
BUILTIN_SENSITIVE_TOOL_NAMES: FrozenSet[str] = frozenset({
    "save_user_memory",
})

# 服务插件工具：plugin_key → 该插件提供的工具。
# plugin_key 必须与 account 库 `plugin.plugin_key` 一致，否则网关算出来的
# `LifeMind-User-Plugins` 永远匹配不上，表现为「用户勾了插件但 agent 什么都不做」。
#
# 一个插件对应「一组工具」，不是一个接口对应一个插件。
# 典型的例子是待办：它没有 todo_* 工具，而是 schedule_create_item 的
# item_type=todo 分支，和日程同表同工具。所以这里只有 schedule 一个 key，
# account 库的 plugin 表也只应有 schedule 一行——多出来的 key 是个死开关，
# 用户能点但没有任何能力随之启停。
SERVICE_TOOLS: Dict[str, Tuple[BaseTool, ...]] = {
    "schedule": SCHEDULE_TOOLS,
}

# 工具名 → 所属插件 key。由 SERVICE_TOOLS 派生，不要手写。
_TOOL_PLUGIN: Dict[str, str] = {
    tool.name: plugin_key
    for plugin_key, tools in SERVICE_TOOLS.items()
    for tool in tools
}

# 工具名 → 工具对象。内置与服务工具合并，供 run_tool 按名字找实现。
_TOOL_INDEX: Dict[str, BaseTool] = {
    tool.name: tool
    for tool in (*BUILTIN_TOOLS, *(t for tools in SERVICE_TOOLS.values() for t in tools))
}

# ==================== 前端展示约定 ====================

# 各服务面板的视图名。前端按 (plugin, view) 路由到自己的子页面，
# 后端不关心页面怎么画，只保证这个枚举稳定。新增视图必须前后端同时加。
SERVICE_UI_VIEWS: Dict[str, Tuple[str, ...]] = {
    "schedule": ("list", "week", "detail", "memo", "stats", "reminder"),
}

# 工具 → 执行时前端该展示什么。
#
# 字段含义：
# - label：动作名，给前端显示「正在创建事项」这类文案，比工具名短且是中文；
# - view：该打开哪个视图（取值必须在 SERVICE_UI_VIEWS 里）；
# - date：按顺序在这些参数名里找第一个非空的，截取日期部分（前 10 字符）作为
#         `params.date`，让面板直接定位到那一天；不填表示这个工具没有日期概念；
# - id：取这个参数的整数作为 `params.id`，让详情面板知道该显示哪一条。
#
# 只取 date 和 id 两类，不把工具入参原样透传：入参里可能有 title、content
# 这类用户原文，前端不需要，透传等于把对话内容多复制一份到事件流里。
_TOOL_UI: Dict[str, Dict[str, Any]] = {
    "schedule_create_item": {"label": "创建事项", "view": "week", "date": ("start_time", "due_time", "end_time")},
    "schedule_update_item": {"label": "修改事项", "view": "detail", "id": "item_id"},
    "schedule_complete_item": {"label": "完成事项", "view": "list", "id": "item_id"},
    "schedule_cancel_item": {"label": "取消事项", "view": "list", "id": "item_id"},
    "schedule_delete_item": {"label": "删除事项", "view": "list"},
    "schedule_batch_complete": {"label": "批量完成", "view": "list"},
    "schedule_batch_delete": {"label": "批量删除", "view": "list"},
    "schedule_get_item": {"label": "查看事项", "view": "detail", "id": "item_id"},
    "schedule_list_items": {"label": "查看列表", "view": "list", "date": ("date", "start_date")},
    "schedule_overview": {"label": "查看概览", "view": "stats"},
    "schedule_daily_stats": {"label": "查看统计", "view": "stats", "date": ("start_date",)},
    "schedule_upcoming_reminders": {"label": "查看提醒", "view": "reminder"},
    "schedule_create_series": {"label": "创建重复计划", "view": "week", "date": ("base_time",)},
    "schedule_update_series": {"label": "修改重复计划", "view": "list"},
    "schedule_delete_series": {"label": "停止重复计划", "view": "list"},
    "schedule_list_series": {"label": "查看重复计划", "view": "list"},
    "schedule_memo_create": {"label": "新建备忘", "view": "memo"},
    "schedule_memo_update": {"label": "修改备忘", "view": "memo", "id": "memo_id"},
    "schedule_memo_delete": {"label": "删除备忘", "view": "memo"},
    "schedule_memo_get": {"label": "查看备忘", "view": "memo", "id": "memo_id"},
    "schedule_memo_list": {"label": "查看备忘列表", "view": "memo"},
}


def _validate_registry() -> None:
    """
    导入时自检登记表，写错就启动失败，而不是等用户点了插件才发现工具不生效。

    检查四件事：
    1. 工具名必须以「插件key_」开头——前缀是判断归属的兜底依据，也方便人肉排查；
    2. 工具名不能重复——重名会让 `_TOOL_INDEX` 悄悄少一个，模型调用时取到另一个实现；
    3. 只读名单里的每个名字都得真实存在——拼错了不会报错，只会让那个工具变成
       「敏感」，多弹一次确认，很难被发现；
    4. 面板映射里的工具必须真实存在、view 必须在该服务的视图枚举里——
       漏登记的表现是「工具执行完前端什么都不显示」，比报错更难查。
    """
    seen: Dict[str, str] = {}

    for plugin_key, tools in SERVICE_TOOLS.items():
        for tool in tools:
            name = tool.name
            if not name.startswith(f"{plugin_key}_"):
                raise RuntimeError(
                    f"服务工具 {name} 的前缀与插件 key {plugin_key} 不符，"
                    f"应命名为 {plugin_key}_xxx"
                )
            if name in seen:
                raise RuntimeError(f"服务工具重名：{name} 同时登记在 {seen[name]} 与 {plugin_key}")
            seen[name] = plugin_key

    unknown = READONLY_TOOL_NAMES - set(seen)
    if unknown:
        raise RuntimeError(f"只读名单里有未登记的工具名：{sorted(unknown)}")

    for tool_name, rule in _TOOL_UI.items():
        plugin_key = seen.get(tool_name)
        if plugin_key is None:
            raise RuntimeError(f"面板映射里的 {tool_name} 不是已登记的服务工具")
        view = rule.get("view")
        allowed = SERVICE_UI_VIEWS.get(plugin_key, ())
        if view not in allowed:
            raise RuntimeError(
                f"工具 {tool_name} 的视图 {view} 不在 {plugin_key} 的视图枚举 {allowed} 里"
            )
        if not rule.get("label"):
            raise RuntimeError(f"工具 {tool_name} 的面板映射缺少 label")


_validate_registry()


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
        plugin_key: 插件标识，如 schedule
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


def find_tool(tool_name: str) -> Optional[BaseTool]:
    """
    按名字取工具实现，供 `run_tool` 分发用。

    取不到有两种情况，调用方一句话回给模型即可：工具名写错了，
    或这个工具属于用户没启用的插件（此时它压根没绑定给模型，
    正常情况下模型也不会调用它）。

    Args:
        tool_name: 模型给出的工具名

    Returns:
        Optional[BaseTool]: 工具对象，找不到为 None
    """
    return _TOOL_INDEX.get(tool_name)


def plugin_of_tool(tool_name: str) -> Optional[str]:
    """
    查工具属于哪个插件。内置工具返回 None。

    `run_tool` 在执行前用它做一次「插件是否仍然启用」的复核：
    绑定是在请求开始时算的，而一轮任务可能跑很久，中途用户停用了插件，
    不复查就会继续执行下去。
    """
    return _TOOL_PLUGIN.get(tool_name)


def is_sensitive_tool(tool_name: str) -> bool:
    """
    判断工具是否需要用户逐次确认。

    默认值刻意选「敏感」：服务工具除非显式登记为只读，一律要确认。
    这个方向的代价不对称——漏标只读工具只是多弹一次窗，
    漏标写操作就是模型能不经同意删掉用户的日程。

    Args:
        tool_name: 工具名

    Returns:
        bool: True 表示需要人工确认
    """
    if tool_name in BUILTIN_SENSITIVE_TOOL_NAMES:
        return True

    # 既不是内置敏感工具，也不是服务工具（例如未知工具名）→ 返回 False 不会造成越权：
    # 未知工具在 _TOOL_INDEX 里找不到实现，根本执行不了
    if tool_name not in _TOOL_PLUGIN:
        return False

    return tool_name not in READONLY_TOOL_NAMES


# 工具收到的日期参数可能长成 2026-11-21、2026/11/21 09:05、2026.11.21 等，
# 一概归一成 2026-11-21：前端要拿它直接定位日历，格式分叉等于每个插件各写一遍解析。
_DATE_IN_TEXT = re.compile(r"(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})")


def _normalize_date(raw: Any) -> str:
    """
    把各种日期写法收敛成 YYYY-MM-DD。

    识别不出来的（例如毫秒时间戳）不猜，原样截前 10 位给前端，由前端自行决定怎么用——
    这里猜错比不猜更糟，会让面板定位到错误的一天。

    Args:
        raw: 工具入参里的日期值，可能是字符串、数字

    Returns:
        str: YYYY-MM-DD，或原始文本的截断
    """
    text = str(raw).strip()
    matched = _DATE_IN_TEXT.search(text)
    if matched:
        year, month, day = matched.groups()
        return f"{year}-{int(month):02d}-{int(day):02d}"
    return text[:10] if len(text) >= 10 else text


def tool_label(tool_name: str) -> str:
    """
    取工具的动作名，供流式事件里展示「正在创建事项」。

    没登记面板的工具（内置工具）直接回工具名，调用方本来就不该推它，
    这里只是避免 KeyError。

    Args:
        tool_name: 工具名

    Returns:
        str: 中文动作名，或工具名本身
    """
    rule = _TOOL_UI.get(tool_name)
    if rule and rule.get("label"):
        return str(rule["label"])
    return tool_name


def ui_panel_of(
        tool_name: str,
        tool_args: Optional[Dict[str, Any]] = None,
) -> Optional[Dict[str, Any]]:
    """
    算出某个工具执行时前端该展示的面板。

    只在工具**开始执行**时调用一次：这时参数已知，可以定位到具体日期或具体那条数据；
    结束时参数拿不到，调用方用 `tool_label` 补文案即可。

    返回的 `params` 只含 date 和 id 两类，不放工具原始入参——入参里有用户的
    原话（标题、正文），前端用不上，透传等于把对话内容多抄一份到事件流。

    Args:
        tool_name: 工具名
        tool_args: 模型给这个工具的参数

    Returns:
        Optional[Dict]: {plugin, view, label, params}；该工具不展示面板时为 None
    """
    rule = _TOOL_UI.get(tool_name)
    plugin_key = _TOOL_PLUGIN.get(tool_name)
    if rule is None or plugin_key is None:
        return None

    args = tool_args or {}
    params: Dict[str, Any] = {}

    for field in rule.get("date") or ():
        raw = args.get(field)
        if raw:
            params["date"] = _normalize_date(raw)
            break

    id_field = rule.get("id")
    if id_field:
        raw = args.get(id_field)
        if raw is not None:
            try:
                params["id"] = int(raw)
            except (TypeError, ValueError):
                # 模型偶尔把 id 写成字符串或带引号，转不动就当没有，不要因此不发面板
                pass

    return {
        "plugin": plugin_key,
        "view": rule["view"],
        "label": rule["label"],
        "params": params,
    }
