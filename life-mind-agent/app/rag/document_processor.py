from typing import List
from langchain_core.documents import Document

async def llm_memory_processor(content: str) -> List[Document]:
    """
    对话长期记忆处理器。

    接收用户或 AI 的对话文本，将其切分为适合 Embedding 的 Chunk 块，
    并封装为 LangChain 的 Document 对象，以供后续存入 Milvus 向量数据库。

    Args:
        content (str): 待处理的对话文本内容（通常为用户提问或 AI 回复）。

    Returns:
        List[Document]: 包含切分后文本及元数据（Metadata）的文档列表。
    """
    if len(content) <= 500:
        Document(
            page_content=content,
            metadata={

            }
        )

