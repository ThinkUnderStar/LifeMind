from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from langgraph.store.postgres import AsyncPostgresStore

from app.core.settings import settings

#全局静态组件
saver = None
store = None
_saver_cm = None
_store_cm = None

async def connect_postgresql_db():
    """
    连接postgresql服务端

    :return: None
    """
    global saver, store, _saver_cm, _store_cm

    _saver_cm = AsyncPostgresSaver.from_conn_string(settings.POSTGRES_URL)
    _store_cm = AsyncPostgresStore.from_conn_string(settings.POSTGRES_URL)

    # 连接postgresql服务端
    saver = await _saver_cm.__aenter__()
    store = await _store_cm.__aenter__()

    # 建表
    await saver.setup()
    await store.setup()

    print("✅ PostgreSQL 记忆库初始化并建表成功")

async def close_postgresql_db():
    """
    关闭与postgresql的连接

    :return: None
    """
    if saver:
        await _saver_cm.__aexit__(None, None, None)

    if store:
        await _store_cm.__aexit__(None, None, None)

    print("✅ PostgreSql 服务连接已成功断开")