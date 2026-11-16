from fastapi import FastAPI
from langgraph.constants import START, END
from langgraph.graph import StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.nodes import (llm_node, relevant_user_memories,
                             save_human_session_memory, save_ai_session_memory, run_tool, gate, violation_reply, recall,
                             context_build, assemble, planner, approval_gate, check_step, finalize, responder,
                             compress_conversation)
from app.db.postgresql.session import get_saver, get_store
from app.schemas.graph.state import State

# 条件边判断函数
#=======================================================================================================================
def route_after_gate(state: State) -> str:
    """判断此次对话的类型"""
    # 0:违规对话 1:纯对话 2:需要执行任务
    chat_type = state.type
    if chat_type == 0:
        return "violation_reply"
    else:
        return
#=======================================================================================================================

# 创建图，添加节点，构建agent流程图
#=======================================================================================================================
graph = StateGraph(State)
#=======================================================================================================================

#添加节点
#=======================================================================================================================
graph.add_node("save_human_session_memory", save_human_session_memory)
graph.add_node("relevant_user_memories", relevant_user_memories)
graph.add_node("llm_node", llm_node)
graph.add_node("save_ai_session_memory", save_ai_session_memory)
graph.add_node("run_tool",run_tool)
graph.add_node("gate",gate)
graph.add_node("violation_reply",violation_reply)
graph.add_node("recall",recall)
graph.add_node("context_build",context_build)
graph.add_node("assemble",assemble)
graph.add_node("planner",planner)
graph.add_node("approval_gate",approval_gate)
graph.add_node("check_step",check_step)
graph.add_node("finalize",finalize)
graph.add_node("responder",responder)
graph.add_node("compress_conversation",compress_conversation)
#=======================================================================================================================

#创建边
#=======================================================================================================================
graph.add_edge(START,"save_human_session_memory")
graph.add_edge("save_human_session_memory","gate")
graph.add_conditional_edges("gate",route_after_gate)
#=======================================================================================================================


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
