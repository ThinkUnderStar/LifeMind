from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.orm import declarative_base

from app.core.settings import settings

# 异步数据库引擎，连接串通过 MYSQL_URL 配置（.env / 环境变量 / Nacos 覆盖）
_AsyncSessionLocal = None
_async_engine = None

def init_mysql_db():
    """
    创建MYSQL数据库引擎并后见会话工厂

    :return: 会话对象得类
    """
    global _AsyncSessionLocal, _async_engine

    if _AsyncSessionLocal is None:
        if _async_engine is None:
            _async_engine = create_async_engine(
                settings.MYSQL_URL,
                pool_size=20,
                max_overflow=30,
                pool_pre_ping=True,
                pool_recycle=3600,
            )

        _AsyncSessionLocal = async_sessionmaker(
            bind=_async_engine,
            autocommit=False,
            autoflush=False,
            expire_on_commit=False,
        )

        print("✅ MySQL 数据库引擎初始化成功")


Base = declarative_base()

async def get_db() -> AsyncSession:
    """FastAPI 依赖：获取数据库会话"""
    if _AsyncSessionLocal is None:
        raise RuntimeError("❌ 数据库尚未初始化，请先在 lifespan 中调用 init_mysql_db()")
    else:
        async with _AsyncSessionLocal() as session:
            yield session
