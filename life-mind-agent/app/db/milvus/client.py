from pymilvus import AsyncMilvusClient
from app.core.settings import settings

# 全局静态组件
milvus_client: AsyncMilvusClient = None

def init_milvus_client():
    """
    获取milvus客户端对象

    :return: None
    """
    global milvus_client

    if milvus_client is None:
        # 获取milvus客户端对象
        milvus_client = AsyncMilvusClient(
            uri=settings.MILVUS_URI,
            token=settings.MILVUS_TOKEN,
            db_name=settings.MILVUS_DB_NAME
        )

        print("✅ Milvus 向量数据库客户端对象初始化成功")

def get_milvus_client() -> AsyncMilvusClient:
    """外部唯一入口：返回已初始化的客户端，未初始化直接抛错。"""
    global milvus_client
    if milvus_client is None:
        raise RuntimeError("Milvus 客户端未初始化，检查 lifespan 的 init 流程")
    return milvus_client

