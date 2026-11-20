"""
Agent 工具包。

外部统一从 `app.agent.tools` 导入，不要直接写内部模块路径（如
`app.agent.tools.agent_tools`）：按职责拆文件时，调用方不用跟着改。

两个模块：
- agent_tools：agent 自身的内置工具（记忆读写、任务清单、联网搜索），
  不属于任何服务插件，所有用户都可用；
- service_tools：组件服务工具，每个工具都归属某个插件（当前只有日程插件），
  由 `service_catalog.SERVICE_TOOLS` 登记「插件 key → 工具对象」，
  只有用户启用该插件时才会绑定给模型。

工具对象本身不必都从本包取，但同包内模块之间（以及 service_catalog）请走这里，
这样新增插件时只需要在下面补一段导出。
"""
from app.agent.tools.agent_tools import (
    USER_MEMORY_ITEM_MAX_CHARS,
    USER_MEMORY_MAX_ITEMS,
    USER_MEMORY_NAMESPACE,
    USER_MEMORY_TIME_FORMAT,
    recall_memory,
    save_user_memory,
    update_tasks,
    web_search,
)
from app.agent.tools.service_tools import (
    READONLY_TOOL_NAMES,
    SCHEDULE_TOOLS,
    schedule_batch_complete,
    schedule_batch_delete,
    schedule_cancel_item,
    schedule_complete_item,
    schedule_create_item,
    schedule_daily_stats,
    schedule_delete_item,
    schedule_get_item,
    schedule_list_items,
    schedule_memo_create,
    schedule_memo_delete,
    schedule_memo_get,
    schedule_memo_list,
    schedule_memo_update,
    schedule_overview,
    schedule_update_item,
    schedule_upcoming_reminders,
)

__all__ = [
    # 用户级记忆的命名空间与容量约束：nodes.py 读取记忆时要与写入侧用同一份
    "USER_MEMORY_NAMESPACE",
    "USER_MEMORY_TIME_FORMAT",
    "USER_MEMORY_ITEM_MAX_CHARS",
    "USER_MEMORY_MAX_ITEMS",
    # 内置工具对象
    "save_user_memory",
    "update_tasks",
    "recall_memory",
    "web_search",
    # 日程插件：工具集合与只读名单（service_catalog 直接用这两个登记）
    "SCHEDULE_TOOLS",
    "READONLY_TOOL_NAMES",
    "schedule_create_item",
    "schedule_update_item",
    "schedule_complete_item",
    "schedule_cancel_item",
    "schedule_delete_item",
    "schedule_batch_complete",
    "schedule_batch_delete",
    "schedule_get_item",
    "schedule_list_items",
    "schedule_overview",
    "schedule_daily_stats",
    "schedule_upcoming_reminders",
    "schedule_memo_create",
    "schedule_memo_update",
    "schedule_memo_delete",
    "schedule_memo_get",
    "schedule_memo_list",
]
