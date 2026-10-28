import logging
from typing import List

from langchain_classic.retrievers import MultiQueryRetriever, ContextualCompressionRetriever
from langchain_classic.retrievers.document_compressors import DocumentCompressorPipeline
from langchain_community.vectorstores.milvus import Milvus
from langchain_core.documents import Document

from app.component_manager.rag_component import CacheEmbeddingManager, MultiQueryLlmManager, RerankManager, \
    ExtractorManager
from app.core.settings import settings
from app.db.milvus.client import get_milvus_client


async def rag_search(question: str, collection_name: str) -> List[Document]:
    # 将要操作的集合加载到内存中
    milvus_client = get_milvus_client()
    await milvus_client.load_collection(collection_name)

    # 构建对该集合的操作对象
    vector_store = Milvus(
        embedding_function=CacheEmbeddingManager.get_cache_embeddings(),
        collection_name=collection_name,
        text_field="text",
        vector_field="vector",
        connection_args={
            "uri": settings.MILVUS_URI,
            "token": settings.MILVUS_TOKEN,
            "db_name": settings.MILVUS_DB_NAME
        },
    )

    # 构建变体查询检索器
    multi_query_retriever = MultiQueryRetriever.from_llm(
        retriever=vector_store.as_retriever(
            search_kwargs={
                "k": 20
            }
        ),
        llm=MultiQueryLlmManager.get_multi_query_llm()
    )

    # 构建压缩管道
    pipeline = DocumentCompressorPipeline(
        transformers=[
            RerankManager.get_reranker(), # 精排序，保留相关度最高的top_n条
            ExtractorManager.get_extractor(), # 提取摘要
        ]
    )

    retriever = ContextualCompressionRetriever(
        base_retriever=multi_query_retriever,
        base_compressor=pipeline,
    )

    logging.info(f"rag_ask 开始检索 {collection_name}")
    search_result = await retriever.ainvoke(question)
    logging.info(f"rag_ask 检索完成 {collection_name} 命中 {len(search_result)} 条")

    await milvus_client.release_collection(collection_name)

    return search_result