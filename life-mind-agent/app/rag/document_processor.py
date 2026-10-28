from typing import List
from langchain_core.documents import Document
from app.component_manager.rag_component import TextSplitterManager
from app.db.mysql import ChatMessageEntity


async def llm_memory_processor(message: ChatMessageEntity) -> List[Document]:
    """
    对话长期记忆处理器。

    接收关联的数据库消息实体，提取对话文本。如果文本长度未超过阈值（500字），
    则直接封装为 LangChain 的 Document 对象；如果超长，则进行切分（Chunking）。
    最终注入 user_id、conversation_id 等元数据，以供后续存入 Milvus 向量数据库。

    Args:
        message (ChatMessageEntity): 关联的数据库消息实体。
            需包含 content（对话文本）、id（消息ID）、conversation_id（会话ID）、
            role（角色）以及 user_id（用户ID）等字段。

    Returns:
        List[Document]: 包含处理后的文本及元数据（Metadata）的文档列表。
                        如果消息内容为空，返回空列表。
    """
    if len(message.content) <= 500:
         document = Document(
            page_content=message.content,
            metadata={
                "id": message.id,
                "conversation_id": message.conversation_id,
                "role": message.role,
                "status": message.status,
                "create_time": str(message.create_time),
                "update_time": str(message.update_time),
            }
        )

         return [document]

    #获取文本切分对象
    text_splitter = TextSplitterManager.get_text_splitter()

    document_list = []
    for content in text_splitter.split_text(message.content):
        document_list.append(
            Document(
                page_content=content,
                metadata={
                    "id": message.id,
                    "conversation_id": message.conversation_id,
                    "role": message.role,
                    "status": message.status,
                    "create_time": message.create_time,
                    "update_time": message.update_time,
                }
            )
        )

    return document_list