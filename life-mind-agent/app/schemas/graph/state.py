#创建节点间的通讯类
from typing import Annotated, List

from langchain_core.messages import BaseMessage
from langgraph.graph import add_messages
from pydantic import BaseModel

from app.db.mysql.entities import AgentTaskEntity


class State(BaseModel):
    messages: Annotated[List[BaseMessage],add_messages]
    # 0:违规对话 1:纯对话 2:需要执行任务
    type: int
    # 相关得端用户级记忆
    relevant_user_memories: str
    # 相关的会话级记忆
    relevant_session_memories: str
    #任务列表
    task_list: List[AgentTaskEntity]
    #临时任务记忆
    task_messages: List[BaseMessage]

