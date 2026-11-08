"""
检索链路：问题 → 向量召回 → 阈值过滤 → 精排 → 送 LLM。

三段各自解决一个不同的问题，不能互相替代：
1. 召回（多查询）：把一个问题改写成几种问法分别检索，扩大命中面；
2. 阈值过滤：丢掉语义上不相关的候选，避免"总有几个近似块"混进精排；
3. 精排：交叉编码器逐条重打分，把真正回答问题的排到最前。
"""
import logging
from typing import List

from langchain_classic.retrievers import MultiQueryRetriever
from langchain_community.vectorstores.milvus import Milvus
from langchain_core.documents import Document

from app.component_manager.rag_component import CacheEmbeddingManager, MultiQueryLlmManager, RerankManager
from app.core.settings import settings
from app.db.milvus.client import get_milvus_client
from app.rag.retriever import CosineTopKRetriever

logger = logging.getLogger(__name__)


async def rag_search(question: str, collection_name: str) -> List[Document]:
    """
    检索与问题相关的文档片段。

    :param question: 用户问题原文，不做预处理（改写由多查询检索器负责）
    :param collection_name: 目标 collection
    :return: 精排后的文档列表，最多 RETRIEVE_FINAL_TOP_K 条；无候选或无一条通过阈值时返回空列表

    Note:
        不挂 LLMChainExtractor：它给每条命中块各调一次小模型做抽取，
        3B 级模型经常漏句或改写，抽掉的往往正是答案句，代价是 N 次串行调用。
        压缩交给"阈值过滤 + 精排"完成，纯规则、零模型调用。
    """
    milvus_client = get_milvus_client()
    # 集合要先 load 才能搜索，必须放 try 外面：load 失败没有可释放的资源
    await milvus_client.load_collection(collection_name)

    try:
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

        # 阈值在这一层生效：拿 Milvus 原始余弦比，不经过 langchain 的分数换算
        retriever = CosineTopKRetriever(
            store=vector_store,
            k=settings.RETRIEVE_TOP_K,
            threshold=settings.RETRIEVE_SCORE_THRESHOLD,
        )

        # 变体生成属于可选增强：改写模型没配好或输出格式不对时必须降级为单查询，
        # 否则一次改写失败会让整条检索挂掉
        try:
            candidates = await MultiQueryRetriever.from_llm(
                retriever=retriever,
                llm=MultiQueryLlmManager.get_multi_query_llm(),
            ).ainvoke(question)
        except Exception as e:
            logger.warning("多查询扩展失败，降级为单查询：%s: %s", type(e).__name__, e)
            candidates = await retriever.ainvoke(question)

        if not candidates:
            logger.info("rag 无候选通过阈值 %.2f collection=%s", settings.RETRIEVE_SCORE_THRESHOLD, collection_name)
            return []

        # 精排只在候选上跑：交叉编码器逐条推理，条数直接决定耗时
        reranked = await RerankManager.get_reranker().acompress_documents(candidates, question)
        # 精排的 top_n 是"保留几条候选"，这里是"最终给模型几条"，两者不同：
        # 前者给人工复查留余量，后者直接决定上下文长度
        result = reranked[: settings.RETRIEVE_FINAL_TOP_K]
        logger.info(
            "rag 检索完成 collection=%s 候选 %s 条 → 精排 %s 条 → 送模型 %s 条",
            collection_name, len(candidates), len(reranked), len(result),
        )
        return result

    finally:
        # 释放失败不能覆盖真正的检索异常，所以单独吃掉只记日志
        try:
            await milvus_client.release_collection(collection_name)
        except Exception as e:
            logger.warning("release_collection 失败 collection=%s: %s", collection_name, e)
