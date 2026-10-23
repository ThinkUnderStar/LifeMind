import logging
from contextlib import asynccontextmanager
from typing import AsyncContextManager

from fastapi import FastAPI
from v2.nacos import RegisterInstanceParam, DeregisterInstanceParam

from app.config.nacos_config import get_nacos_service
from app.config.settings import settings
from app.util.server_tool import get_local_ip


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncContextManager:
    """
    FastAPI 应用生命周期管理器 (Lifespan)。

    负责在应用启动时初始化与外部服务（如数据库、Redis、Nacos等）的长连接，
    并在应用关闭时优雅地释放这些连接，防止资源泄露和僵尸节点。

    :param app: FastAPI 应用实例。由框架在启动时自动注入，
                可用于将全局连接对象挂载到 app.state 上供路由使用。
    :return: 异步上下文管理器（yield 之前的代码在启动时执行，yield 之后的代码在关闭时执行）。
    """

    logger = logging.getLogger(__name__)
    nacos_name_client = await get_nacos_service()
    local_ip = get_local_ip()

    # 构建注册参数
    register_param = RegisterInstanceParam(
        service_name=settings.APP_NAME,  # 服务名，比如 "life-mind-agent"
        group_name=settings.NACOS_GROUP,  # 分组，默认 "DEFAULT_GROUP"
        ip=local_ip,  # 本机 IP
        port=settings.SERVER_PORT,  # 服务端口
        ephemeral=True,  # True 为临时实例（自动心跳）
    )

    await nacos_name_client.register_instance(request=register_param)
    print(f"🚀 服务 [{settings.APP_NAME}] 注册成功: {local_ip}:{settings.SERVER_PORT}")

    yield

    logger.info("🔌 正在关闭系统依赖...")

    # 1. 注销服务 (Naming)
    await nacos_name_client.deregister_instance(DeregisterInstanceParam(
        service_name=settings.APP_NAME,
        group_name=settings.NACOS_GROUP,
        ip=local_ip,
        port=settings.SERVER_PORT
    ))
    logger.info("✅ Nacos 服务已注销")