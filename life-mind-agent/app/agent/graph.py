from typing import List

from fastapi import FastAPI
from langgraph.constants import START, END
from langgraph.graph import StateGraph
from langgraph.graph.state import CompiledStateGraph

from app.agent.nodes import (llm_node, relevant_user_memories,
                             save_human_session_memory, save_ai_session_memory, run_tool, gate, violation_reply, recall,
                             context_build, assemble, planner, approval_gate, check_step, finalize, responder,
                             compress_conversation, generate_task_bind_id)
from app.db.postgresql.session import get_saver, get_store
from app.schemas.graph.state import State

# 条件边判断函数
# 约定：只读 State、返回下一个（或下一批）节点名，不调模型、不写库、不改 State。
# 返回 list[str] = 这些节点在同一 superstep 并行执行；返回 str = 单节点。
# 返回的名字必须是 add_node 注册过的，拼错会在运行时才暴露（这里没给 path_map）。
#=======================================================================================================================
def route_after_gate(state: State) -> List[str]:
    """按 gate 写的 type 分流：违规单走回复节点，正常两路并行取记忆。

    返回 list 是刻意的：relevant_user_memories 与 recall 只读各自的库、写不同 State
    字段，可安全并行；汇合点 context_build 会等两条都完成才执行。
    """
    # 0:违规对话 1:纯对话 2:需要执行任务
    chat_type = state.type
    if chat_type == 0:
        return ["violation_reply"]
    else:
        return ["relevant_user_memories","recall"]

def route_after_context(state: State) -> str:
    """记忆装配完成后分流：强制纯对话或 type=1 走 responder，其余（2 任务）走 planner。

    两个条件取或，任一成立就走纯对话：
    - state.chat_mode == "chat"：用户在界面上显式声明了纯对话模式。这是硬开关，
      不依赖 gate 小模型理解意图（实测 qwen2.5:3b 不守长提示词里的「禁止」类规则），
      所以哪怕 gate 判成 2，也不进任务链、不建任务、不动组件。
    - state.type == 1：gate 判为纯对话。
    违规（type=0）已在 route_after_gate 分走，到不了这里，所以 chat 模式仍会先被拦违规。

    chat 与 task 共用 N4/N5/N6 三层上下文，不能在 gate 处提前拆开。
    """
    # 0:违规对话 1:纯对话 2:需要执行任务
    if state.chat_mode == "chat" or state.type == 1:
        return "responder"
    else:
        return "planner"

def route_after_llm(state: State) -> str:
    """按 llm_node 写回 task_messages 的最后一条 AIMessage 是否带 tool_calls 分流。

    有 → approval_gate（确认后进 run_tool 回填 ToolMessage，再回到 llm_node）；
    无 → check_step（本任务收尾）。读 task_messages 而非主 messages，和 llm_node 写入一致。
    不设 route_after_approval：approve / reject 都要进 run_tool 回填 ToolMessage。
    """
    last_message = state.task_messages[-1]
    tool_calls = getattr(last_message, "tool_calls", None)

    if tool_calls:
        return "approval_gate"
    else:
        return "check_step"

def route_after_step(state: State) -> str:
    """按 check_step 写的 has_tasks 决定是否继续：True 回 assemble 取下一条，False 去 finalize。

    只有两向 —— failed / retrying 状态已砍掉，不设「失败重规划」第三向；
    has_tasks 是 bool，装不下三态。
    """
    has_tasks = state.has_tasks
    if has_tasks:
        return "assemble"
    else:
        return "finalize"
#=======================================================================================================================

# 创建图，添加节点，构建agent流程图
#=======================================================================================================================
graph = StateGraph(State)
#=======================================================================================================================

#添加节点
#=======================================================================================================================
graph.add_node("save_human_session_memory", save_human_session_memory)
graph.add_node("generate_task_bind_id", generate_task_bind_id)
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
graph.add_edge("save_human_session_memory","generate_task_bind_id")
graph.add_edge("generate_task_bind_id","gate")
graph.add_conditional_edges("gate",route_after_gate)
graph.add_edge("violation_reply","save_ai_session_memory")
graph.add_edge("relevant_user_memories","context_build")
graph.add_edge("recall","context_build")
graph.add_conditional_edges("context_build",route_after_context)
graph.add_edge("planner","assemble")
graph.add_edge("assemble","llm_node")
graph.add_conditional_edges("llm_node",route_after_llm)
graph.add_edge("approval_gate","run_tool")
graph.add_edge("run_tool","llm_node")
graph.add_conditional_edges("check_step",route_after_step)
graph.add_edge("finalize","responder")
graph.add_edge("responder","save_ai_session_memory")
graph.add_edge("save_ai_session_memory","compress_conversation")
graph.add_edge("compress_conversation",END)
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
