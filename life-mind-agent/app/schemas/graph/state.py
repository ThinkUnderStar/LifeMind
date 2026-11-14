#创建节点间的通讯类
from typing import Annotated, List, Optional

from langchain_core.messages import BaseMessage
from langgraph.graph import add_messages
from pydantic import BaseModel


class TaskItem(BaseModel):
    """State 内的任务数据对象：agent_task 表一行的纯数据副本。

    与 AgentTaskEntity（SQLAlchemy ORM 实体）字段对应，但不绑定数据库会话，
    能安全放进 State 并被 checkpoint 序列化。ORM 实体只在节点落库时构造，
    绝不写进 State；落库成功后用 refresh 回填的值创建本对象放入 task_list。
    """

    # 落库自增主键，refresh 后回填；未落库为 None
    id: Optional[int] = None
    # 会话ID，判断同一对话的任务只靠这一列
    conversation_id: Optional[int] = None
    # 本任务目标
    goal: str
    # 任务状态：pending / running / done / failed / retrying
    status: str = "pending"
    # 本任务的执行结果，未执行完为 None
    result: Optional[str] = None


class State(BaseModel):
    messages: Annotated[List[BaseMessage],add_messages]
    # 0:违规对话 1:纯对话 2:需要执行任务
    type: int
    # 相关得端用户级记忆
    relevant_user_memories: str
    # 相关的会话级记忆
    relevant_session_memories: str
    #任务列表
    task_list: List[TaskItem]
    #临时任务记忆
    task_messages: List[BaseMessage]

