"""
向量入库。

把 embedding_docs 产出的行数据写入 Milvus 的指定 collection，
是 RAG 写入链路的最后一步。
"""
from typing import Dict, List

from app.db.milvus.client import get_milvus_client


async def vector_store(embed: List[Dict], collection_name: str) -> None:
    """
    将带向量的文档批量写入 Milvus。

    Args:
        embed: 每项含 text / vector 及若干 metadata 字段，
               字段名必须与 collection schema 严格一致，多或少都会整批写入失败。
        collection_name: 目标 collection，通常按用户或会话隔离。

    Returns:
        None。当前未返回插入结果（主键、成功条数）；
        后续需要回查或做幂等校验时，应改为返回 insert 的 MutationResult。
    """
    # insert 本身是批量接口：data 直接传整个列表，不要在外层再循环逐条写
    await get_milvus_client().insert(collection_name=collection_name, data=embed)
