"""
对话消息 → LangChain Document 的处理器。

这是「消息文本」到「可向量化文档」的唯一入口：节点实时写入和后续从 MySQL 全量回填
都走这里，切分规则只有一份。

产出的 metadata 键必须与 Milvus collection 的标量字段一一对应
（embedding_docs 会把 metadata 全量展开成一行），多一个少一个都会让整批写入失败。
"""
from typing import List

from langchain_core.documents import Document

from app.component_manager.rag_component import TextSplitterManager
from app.db.mysql import ChatMessageEntity


async def llm_memory_processor(message: ChatMessageEntity, user_id: int) -> List[Document]:
    """
    对话长期记忆处理器。

    把一条已落库的消息切分成若干 Document，每个 Document 对应 Milvus 里一行向量。
    长度判断不做特例分支：切分器的 chunk_size 是 512，短文本本来就只会切出 1 块，
    留两套 metadata 拼装反而容易改一处漏一处。

    Args:
        message (ChatMessageEntity): 已 commit 的消息实体，必须已被 refresh
            （需要 id 作为回溯键、create_time 作为时间戳）。
        user_id (int): 消息归属用户。chat_message 表本身没有 user_id 列，
            只能由调用方传进来，缺了就无法按用户过滤记忆。

    Returns:
        List[Document]: 每个元素的 metadata 含
            message_id / chunk_index / conversation_id / user_id / role / create_time。
            内容为空时返回空列表（空文本送 embedding 会被供应商拒绝）。

    Note:
        status、update_time 不写进向量库：status 只对「是否失败」有意义（能写入的都是成功的），
        update_time 与召回无关，带上只会逼着 collection 多两列。
    """
    if not message.content or not message.content.strip():
        return []

    # 时间取库里的值（server_default=func.now()）而不是应用进程的 datetime.now()：
    # 前者与 MySQL 同一条时钟，不会出现容器 UTC 与应用本地时间混存
    create_time = message.create_time.isoformat(sep=" ") if message.create_time else ""

    document_list = []
    # chunk_index 必须进 metadata：同一条消息切成 N 行，靠它拼回原文，也靠它进主键避免互相覆盖
    for chunk_index, content in enumerate(TextSplitterManager.get_text_splitter().split_text(message.content)):
        document_list.append(
            Document(
                page_content=content,
                metadata={
                    "message_id": message.id,
                    "chunk_index": chunk_index,
                    "conversation_id": message.conversation_id,
                    "user_id": user_id,
                    "role": message.role,
                    "create_time": create_time,
                }
            )
        )

    return document_list
