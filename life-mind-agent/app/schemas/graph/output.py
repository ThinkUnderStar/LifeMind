"""图内部模型的结构化输出结构。"""
from typing import Literal, List

from pydantic import BaseModel, Field


class GateOutput(BaseModel):
    """gate 节点的结构化输出，直接写回 State.type。"""

    # 0:违规对话 1:纯对话 2:需要执行任务
    # 判据别写在这里：8B 的 gate 模型不读 JSON schema 里的长文本（实测写了也不生效），
    # 完整判据在 prompts.GATE_SYSTEM_PROMPT，由 gate 节点作为 system message 传入。
    type: Literal[0, 1, 2] = Field(
        description=(
            "本轮意图分类：0 = 违规对话，1 = 纯对话，2 = 需要执行任务"
            "（查询/修改数据，或联网检索外部实时信息）。"
        )
    )

class PlannerOutput(BaseModel):
    """任务计划节点结构化输出"""

    # 判据别写在这里：8B 的 planner 模型不读 JSON schema 里的长文本（实测会把英文
    # snake_case 和编造的事实写进 goal），完整的「任务怎么写」在 prompts.PLANNER_SYSTEM_PROMPT，
    # 由 planner 节点作为 system message 传入。
    task_list: List[str] = Field(
        description=(
            "本轮要执行的任务清单，一项任务一个字符串，写简体中文、动词开头、一句能独立看懂的话；"
            "只写做什么，不写结论，不编号。不需要执行任何动作时返回空列表。"
        )
    )