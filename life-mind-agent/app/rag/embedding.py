"""
文档向量化。

把切分好的 Document 送进 embedding 模型，产出可直接写入向量库的行数据：
每条同时带 text（原文）、vector（向量）以及原文档的 metadata
（id / conversation_id / role 等），保证召回后能凭 id 回溯到原始消息。
"""
from typing import Dict, List

from langchain_core.documents import Document

from app.component_manager.rag_component import CacheEmbeddingManager


async def embedding_docs(docs_list: List[Document]) -> List[Dict]:
    """
    批量把文档转成向量，并组装成待入库的行结构。

    Args:
        docs_list: 切分后的文档列表，metadata 里需带 id、conversation_id 等字段。

    Returns:
        List[Dict]: 每项形如 {"text": ..., "vector": [...], "id": ..., ...}，
                    可直接作为 Milvus insert 的 data 使用。

    说明：一次调用只发一轮批量请求，文档越多单次耗时越长；
    文档量很大时应在调用方分批，避免请求超时。
    """
    # 走签名缓存拿实例：模型名和缓存路径没变就复用，不会重复创建
    cache_embeddings = CacheEmbeddingManager.get_cache_embeddings()
    # 用 aembed_documents 而不是逐条 aembed_query：整批一次请求，省 N 倍网络往返
    docs_embed = await cache_embeddings.aembed_documents([doc.page_content for doc in docs_list])

    embed = []
    # 按下标回填原文与 metadata：依赖 aembed_documents 按输入顺序返回
    for i, e in enumerate(docs_embed):
        embed.append(
            {
                "text": docs_list[i].page_content,
                "vector": e,
                # 放在最后展开：metadata 里若恰好有 text / vector 键会覆盖上面两行
                **docs_list[i].metadata
            }
        )

    return embed
