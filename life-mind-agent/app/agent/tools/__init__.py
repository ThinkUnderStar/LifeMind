"""
Agent 工具包。

外部统一从 `app.agent.tools` 导入，不要直接写内部模块路径（如
`app.agent.tools.agent_tools`）：按职责拆文件时，调用方不用跟着改。

当前只有一个模块：
- agent_tools：agent 自身的内置工具（记忆读写、任务清单、联网搜索），
  不属于任何服务插件，所有用户都可用。

将来接服务插件时，在这里新增 service_tools.py 等文件，并把工具名补进下面的
导出列表——`service_catalog.SERVICE_TOOLS` 只登记「插件 key → 工具对象」，
工具对象本身仍然从这个包取。
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
]
