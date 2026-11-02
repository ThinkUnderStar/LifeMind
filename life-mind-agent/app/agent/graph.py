from fastapi import FastAPI
from langgraph.constants import START, END
from langgraph.graph import StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.nodes import (llm_node, relevant_user_memories,
                             save_human_session_memory, save_ai_session_memory)
from app.db.postgresql.session import get_saver, get_store
from app.schemas.graph.state import State

# 创建图，添加节点，构建agent流程图
graph = StateGraph(State)

#添加节点
graph.add_node("save_human_session_memory", save_human_session_memory)
graph.add_node("relevant_user_memories", relevant_user_memories)
graph.add_node("llm_node", llm_node)
graph.add_node("save_ai_session_memory", save_ai_session_memory)

#创建边
# 先注入用户记忆再落库：relevant_user_memories 替换 messages[0] 的 SystemMessage，
# 之后保存用户消息、调用模型时，整个 state 都已带最新记忆
graph.add_edge(START, "relevant_user_memories")
graph.add_edge("relevant_user_memories", "save_human_session_memory")
graph.add_edge("save_human_session_memory", "llm_node")
graph.add_edge("save_ai_session_memory", END)


def get_graph(app: FastAPI) -> CompiledStateGraph[State]:
    """
    获取编译后的图（单例）。

    编译时需要 saver 与 store，而这两个对象要等 lifespan 里 connect 完才有值，
    所以不能在模块导入时编译，只能在第一次请求时惰性编译。

    :param app: FastAPI 实例，编译结果挂在 app.state.graph 上
    :return: 编译后的图
    """
    # 用 getattr 而不是 app.state.graph：属性从未设置过时直接访问会抛 AttributeError
    if getattr(app.state, "graph", None) is None:
        app.state.graph = graph.compile(checkpointer=get_saver(), store=get_store())

    return app.state.graph
