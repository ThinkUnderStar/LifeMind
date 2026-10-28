"""
RAG 组件族。

按「一份文档从入库到被召回」的顺序，这里有五类可替换的组件：
1. TextSplitterManager   —— 切块：长文本 → 适合 embedding 的 chunk
2. CacheEmbeddingManager —— 向量化：文本 → 向量（带本地缓存）
3. MultiQueryLlmManager  —— 查询改写：一个问题 → 多条不同表述，扩大召回面
4. RerankManager         —— 重排：给候选文档重新打分，把最相关的排到最前
5. ExtractorManager      —— 抽取：只留下真正回答问题的句子，压掉无关 token

统一机制：懒加载 + 签名缓存。签名取自各自影响实例行为的配置项，
配置被 Nacos 热更新后签名变化，下次获取自动重建实例；签名不变则复用。
因此这些类都不需要额外的 init / refresh 方法。
"""
import re
from typing import Dict
from langchain_classic.embeddings import CacheBackedEmbeddings
from langchain_classic.retrievers.document_compressors import CrossEncoderReranker, LLMChainExtractor
from langchain_classic.storage import LocalFileStore
from langchain_community.cross_encoders import HuggingFaceCrossEncoder
from langchain_ollama import OllamaEmbeddings, ChatOllama
from langchain_text_splitters import RecursiveCharacterTextSplitter
from app.core.settings import settings


class TextSplitterManager:
    """
    RAG 文本切分器管理器。

    核心机制：签名缓存（Signature Cache），与 ChatLlmManager 保持一致。
    切分参数支持 Nacos 热更新，参数变化后需要拿到新的切分器实例，
    参数不变则复用同一实例，避免每次切分都重建对象。
    """

    # 切分器缓存：{ (chunk_size, chunk_overlap): 切分器实例 }
    _cache: Dict[tuple[int, int], RecursiveCharacterTextSplitter] = {}

    @classmethod
    def get_text_splitter(cls) -> RecursiveCharacterTextSplitter:
        """
        获取当前配置对应的文本切分器。

        以 (chunk_size, chunk_overlap) 作为配置签名：
        - 签名命中缓存：直接复用现有实例；
        - 签名未命中（首次调用，或配置被 Nacos 热更新）：按新参数重建实例。

        Returns:
            RecursiveCharacterTextSplitter: 与当前配置一致的切分器实例。
        """
        # 配置签名，任一项变化都视为"新配置"，需要重建实例
        sig = (
            settings.SPLIT_CHUNK_SIZE,
            settings.SPLIT_CHUNK_OVERLAP,
        )

        if sig not in cls._cache:
            text_splitter = RecursiveCharacterTextSplitter(
                # 单块最大长度（字符），默认 512：过大稀释语义，过小切断上下文
                chunk_size=settings.SPLIT_CHUNK_SIZE,
                # 相邻块重叠长度，默认 64：缓解句子正好被切断；必须小于 chunk_size
                chunk_overlap=settings.SPLIT_CHUNK_OVERLAP,
                # 分隔符优先级：段落 → 换行 → 中文句读 → 空格 → 空串
                # 末尾 "" 表示找不到自然边界时按字符硬切，保证任何文本都切得动
                separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""],
            )

            # 整体替换而非新增：缓存只留当前配置一份，防止热更新后无限膨胀
            cls._cache = {sig: text_splitter}

        return cls._cache[sig]


class CacheEmbeddingManager:
    """
    向量化模型管理器（带本地缓存）。

    核心机制：与 TextSplitterManager 相同的签名缓存，
    底层用 CacheBackedEmbeddings 包一层本地文件缓存，
    同一段文本只请求一次模型，重复内容直接读缓存。

    注意：更换 EMBEDDING_MODEL 会改变向量维度，
    必须同步调整 MILVUS_DIMENSION 并重建 collection，否则写入会维度不匹配。
    """

    # 缓存：{ (模型名, 缓存路径): 带缓存的 embedding 实例 }
    _cache: Dict[tuple[str, str], CacheBackedEmbeddings] = {}

    @classmethod
    def get_cache_embeddings(cls) -> CacheBackedEmbeddings:
        """
        获取当前配置对应的、带缓存的向量化实例。

        以 (模型名, 缓存路径) 作为配置签名，任一变化都重建实例。
        """
        # 配置签名：换模型或换缓存目录都视为新配置
        sig = (
            settings.EMBEDDING_MODEL,
            settings.EMBEDDING_CACHE_PATH
        )

        if sig not in cls._cache:
            cache_embedding = CacheBackedEmbeddings.from_bytes_store(
                # 真正干活的模型：Ollama 本地部署，模型名由配置指定
                underlying_embeddings=OllamaEmbeddings(model=settings.EMBEDDING_MODEL),
                # 缓存落本地文件，key 用文本哈希，命中后不再请求模型
                document_embedding_cache=LocalFileStore(settings.EMBEDDING_CACHE_PATH),
                # 按模型名隔离命名空间：不同模型的向量空间不可混用，不隔离会读到旧模型的向量；
                # 同时把 "qwen3:8b" 里的 : 等字符替换成下划线，避免成为非法 key
                namespace=re.sub(r'[^a-zA-Z0-9_-]', '_', settings.EMBEDDING_MODEL)
            )

            # 整体替换而非新增，缓存只留当前配置一份
            cls._cache = {sig: cache_embedding}

        return cls._cache[sig]


class RerankManager:
    """
    重排器管理器（召回链路的精排环节）。

    向量召回只判断"语义像不像"，会把一堆相近但没用的片段一起捞回来；
    CrossEncoder 把 query 和候选文档拼成一对整体打分，精度明显高于向量相似度，
    代价是要逐条推理，所以只适合处理少量候选（top_n 通常 3~10）。
    """

    # 缓存：{ (重排模型名, 保留条数): 重排器实例 }
    _cache: Dict[tuple[str, int], CrossEncoderReranker] = {}

    @classmethod
    def get_reranker(cls) -> CrossEncoderReranker:
        """
        获取当前配置对应的重排器。

        签名是 (模型名, top_n)：换模型要重新加载权重，改保留条数也要重建实例。

        Returns:
            CrossEncoderReranker: 绑定了 HuggingFace 交叉编码器的本地重排器。
        """
        sig = (
            settings.RERANKER_MODEL,
            settings.RERANKER_TOP_N,
        )

        if sig not in cls._cache:
            reranker = CrossEncoderReranker(
                model = HuggingFaceCrossEncoder(
                    # 本地加载的交叉编码器，真正读权重发生在第一次推理时
                    model_name=settings.RERANKER_MODEL,
                    # 推理设备写死 cuda：没有可用显卡会直接报错，届时改成 cpu
                    model_kwargs={"device": "cuda"}
                ),
                # 重排后保留几条，直接决定后续送进 LLM 的上下文长度
                top_n=settings.RERANKER_TOP_N,
            )

            # 整体替换而非新增，缓存只留当前配置一份
            cls._cache = {sig: reranker}

        return cls._cache[sig]


class ExtractorManager:
    """
    文档压缩器管理器（抽取式）。

    重排只负责排序、不删内容；真正压 token 的是这一步——
    逐条判断"这段里有没有回答问题的句子"：没有就整段丢弃，
    有就只把相关句子摘出来。因此它是最后一环，放在重排之后。
    """

    # 缓存：{ 抽取模型名: 抽取器实例 }
    _cache: Dict[str, LLMChainExtractor] = {}

    @classmethod
    def get_extractor(cls) -> LLMChainExtractor:
        """
        获取当前配置对应的抽取器。

        签名只有模型名，因为唯一影响实例行为的配置就是它。

        Returns:
            LLMChainExtractor: 内部持有一个 Ollama chat 模型。
        """
        sig = settings.EXTRACTOR_MODEL

        if sig not in cls._cache:
            extractor = LLMChainExtractor.from_llm(
                llm=ChatOllama(
                    model=settings.EXTRACTOR_MODEL,
                    # 抽取是"照原文摘句"，不需要发挥，温度压到 0.1
                    temperature=0.1
                )
            )

            # 整体替换而非新增，缓存只留当前配置一份
            cls._cache = {sig: extractor}

        return cls._cache[sig]


class MultiQueryLlmManager:
    """
    多查询改写模型管理器。

    用户一句话里常混着口语、省略和指代，直接拿去检索命中率低。
    这里用独立模型把原问题改写成多条不同表述，分别检索后合并去重，提高召回率。
    """

    # 缓存：{ 改写模型名: chat 模型实例 }
    _cache: Dict[str, ChatOllama] = {}

    @classmethod
    def get_multi_query_llm(cls) -> ChatOllama:
        """
        获取当前配置对应的查询改写模型。

        Returns:
            ChatOllama: 供 MultiQueryRetriever 调用的 chat 模型。
        """
        sig = settings.MULTI_QUERY_MODEL

        if sig not in cls._cache:
            multi_query_llm = ChatOllama(
                model=settings.MULTI_QUERY_MODEL,
                # 温度比对话高：几条改写之间要有差异，太低会改出几乎一样的句子
                temperature=0.4,
            )

            # 整体替换而非新增，缓存只留当前配置一份
            cls._cache = {sig: multi_query_llm}

        return cls._cache[sig]


