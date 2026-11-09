from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    应用全局配置管理类。

    继承自 Pydantic 的 BaseSettings，具备自动从环境变量和 .env 文件中
    读取配置并覆盖默认值的能力，同时自带严格的类型校验。
    """
    # =================== server 相关配置参数 ======================
    APP_NAME: str = "life-mind-agent"
    SERVER_PORT: int = 8004
    SERVER_PATH: str = "/life-mind/agent"

    # =================== nacos 服务配置参数 =======================
    NACOS_SERVER: str = "127.0.0.1:8848"
    NACOS_NAMESPACE: str = ""
    NACOS_GROUP: str = "DEFAULT_GROUP"

    # =================== mysql 数据库配置参数 ======================
    MYSQL_URL: str = ""

    # ================== postgresql 相关配置 ======================
    POSTGRES_URL: str = ""

    # =============== milvus 向量数据库相关配置 =====================
    MILVUS_URI: str = ""
    MILVUS_TOKEN: str = ""
    MILVUS_DB_NAME: str = ""
    MILVUS_DIMENSION: int = 2560

    # =================== 记忆相关配置 ==========================
    # 会话记忆统一存放的 collection，所有用户共用一张表，靠 user_id / conversation_id 过滤
    SESSION_MEMORY_COLLECTION: str = "life_mind_session_memory"

    # =================== agent 相关配置 ==========================
    # 与用户交互的模型配置
    CHAT_MODEL_NAME: str = "chat-model"
    CHAT_MODEL_TEMPERATURE: float = 1.0
    CHAT_MODEL_BASE_URL: str = ""
    CHAT_MODEL_API_KEY: str = ""

    # =================== RAG 相关配置 =====================
    # 文本切分相关配置
    SPLIT_CHUNK_SIZE: int = 512
    SPLIT_CHUNK_OVERLAP: int = 64
    # 检索相关配置
    # 单查询召回的候选条数：要比最终送 LLM 的条数大得多，否则阈值一筛就没东西可精排
    RETRIEVE_TOP_K: int = 40
    # 原始余弦相似度下限：低于它的候选直接丢弃，不参与精排
    # 值域是 [-1, 1]，中文 embedding 常见分档：无关 ≤0.35、相关 0.5+
    RETRIEVE_SCORE_THRESHOLD: float = 0.4
    # 最终送进大模型的条数：再多只涨 token 成本，对答案质量的边际收益很低
    RETRIEVE_FINAL_TOP_K: int = 6
    # 阈值把候选全清空时是否兜底：开启后「问了就一定有东西可答」，
    # 代价是与知识库无关的问题也会拿到几条最相近的切片，模型可能硬答
    RETRIEVE_FALLBACK_ON_EMPTY: bool = False
    # 兜底取几条：不再应用阈值，纯按相似度取最相近的若干块
    RETRIEVE_FALLBACK_TOP_K: int = 10
    # 嵌入模型相关配置
    EMBEDDING_MODEL: str = ""
    EMBEDDING_CACHE_PATH: str = ""
    # 精排相关配置
    RERANKER_MODEL: str = ""
    RERANKER_TOP_N: int = 10
    # 摘要相关配置
    EXTRACTOR_MODEL: str = ""
    # 查询变体相关配置
    MULTI_QUERY_MODEL: str = ""

    # ==================== Pydantic 配置加载规则 ====================
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore"
    )

#创建全局配置类对象
settings = Settings()
