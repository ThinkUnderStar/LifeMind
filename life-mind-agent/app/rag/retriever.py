"""
带阈值过滤的向量检索器。

为什么不用 as_retriever(search_type="similarity_score_threshold")：
它比对前会先过一遍 relevance_score_fn 把原始分数换算成 0~1 的"相关性"，
而换算函数取决于 collection 的度量类型。langchain 拿不到索引信息时按 L2
套 1 - d/4.0，而我们的 collection 是 COSINE、返回的本身就是余弦，于是：
    值域被平移：cos∈[-1,1] → [0.75,1.25]，任何 <0.75 的阈值恒为真，过滤等于没写；
    方向被反转：余弦越高换算值越低，调高阈值反而先筛掉最相关的文档。
所以这里直接拿 similarity_search_with_score 的原始分数比阈值，不经过任何换算。
"""
import logging
from typing import Any, List, Optional, Tuple

from langchain_core.callbacks import CallbackManagerForRetrieverRun
from langchain_core.documents import Document
from langchain_core.retrievers import BaseRetriever

logger = logging.getLogger(__name__)

# 只有这两种度量是「分数越大越相似」，阈值才能直接比大小；
# L2 是距离越小越相似，拿阈值比就是反的，必须先识别出来再决定要不要筛
_SIMILARITY_METRICS = {"COSINE", "IP"}


class CosineTopKRetriever(BaseRetriever):
    """
    取 Top-K 候选，并丢弃原始余弦分低于阈值的。

    继承 BaseRetriever 而不是自己包一层函数，是为了能直接塞进 MultiQueryRetriever：
    多个查询变体会被它并发发出，这里重写异步方法就能走异步检索，不退回线程池。

    :param store: langchain 的 Milvus 实例，需提供 similarity_search_with_score
    :param k: 单查询召回条数，要给阈值过滤留出余量
    :param threshold: 原始余弦下限，低于它的候选直接丢弃
    """

    store: Any
    k: int = 40
    threshold: float = 0.4

    def _metric_type(self) -> str:
        """从 Milvus 实例的检索参数里取度量类型，取不到就按 COSINE 处理。"""
        params = getattr(self.store, "search_params", None) or {}
        return str(params.get("metric_type", "COSINE")).upper()

    def _keep(self, pairs: List[Tuple[Document, float]]) -> List[Document]:
        """
        按阈值筛掉不相关候选。

        :param pairs: (文档, 原始分数) 列表，分数由 Milvus 直接给出、未做换算
        :return: 通过阈值的文档，保持 Milvus 返回的相关性降序
        """
        if not pairs:
            return []

        if self._metric_type() not in _SIMILARITY_METRICS:
            # 度量不是余弦/IP 时阈值方向相反，硬筛会筛掉最相似的，不如不筛并留痕
            logger.warning(
                "collection 度量为 %s（越小越相似），阈值 %.2f 不适用，本次仅做 Top-K 截断",
                self._metric_type(), self.threshold,
            )
            return [doc for doc, _ in pairs]

        kept = [doc for doc, score in pairs if score >= self.threshold]
        # 打出最高/最低分是为了标定阈值：连最高分都够不着，说明阈值定高了或问题本身没答案
        logger.info(
            "余弦过滤 %s → %s 条（阈值 %.2f，候选分数 %.3f~%.3f）",
            len(pairs), len(kept), self.threshold,
            max(score for _, score in pairs), min(score for _, score in pairs),
        )
        return kept

    def _get_relevant_documents(
            self, query: str, *, run_manager: Optional[CallbackManagerForRetrieverRun] = None
    ) -> List[Document]:
        return self._keep(self.store.similarity_search_with_score(query, k=self.k))

    async def _aget_relevant_documents(
            self, query: str, *, run_manager: Optional[CallbackManagerForRetrieverRun] = None
    ) -> List[Document]:
        # 多查询变体由 MultiQueryRetriever 并发发出，走异步接口避免每个变体各占一个线程
        pairs = await self.store.asimilarity_search_with_score(query, k=self.k)
        return self._keep(pairs)
