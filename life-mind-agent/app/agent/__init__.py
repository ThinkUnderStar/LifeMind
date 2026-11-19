"""
Agent 编排包。

LangGraph 的图定义（graph）、State（状态）、节点（nodes）、提示词（prompts）、
工具（tools）都在这里，属于"流程编排"层，不负责实例创建——
模型、切分器等组件的构造与缓存统一放在 component_manager 包中。
"""
