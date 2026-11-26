import asyncio
import logging
from contextlib import asynccontextmanager
from typing import AsyncContextManager, Optional

import httpx
import yaml
from fastapi import FastAPI
from v2.nacos import RegisterInstanceParam, DeregisterInstanceParam, ConfigParam

from app.core.nacos_client import get_nacos_service, get_nacos_config, on_config_changed
from app.core.settings import settings
from app.client.base import close_client
from app.db.milvus.client import init_milvus_client
from app.db.mysql.session import init_mysql_db
from app.db.postgresql.session import connect_postgresql_db, close_postgresql_db
from app.util.server_tool import get_local_ip

# Nacos 临时实例保活：SDK 的 gRPC 长连接被服务端断开后不会自动重注册，实例会被剔除，
# 网关随之报 "No servers available"。这里用独立 HTTP 探测做兜底。
_NACOS_KEEPALIVE_INTERVAL = 5  # 探测间隔（秒），远小于临时实例 15~30s 的过期剔除时间
_NACOS_REBUILD_AFTER = 3       # 连续补注册失败达到该次数则重建 gRPC 客户端


async def _nacos_register_keepalive(
    logger: logging.Logger,
    get_client,
    set_client,
    register_param: RegisterInstanceParam,
) -> None:
    """后台兜底：独立于 SDK 的 gRPC 连接，用 Nacos HTTP OpenAPI 探测本实例是否仍在，
    掉线就补注册；补注册连续失败则重建 naming 客户端（新建 gRPC 连接）。

    get_client/set_client 用于读写 lifespan 持有的当前客户端引用，重建后主流程注销用的是新客户端。
    """
    ip = register_param.ip
    port = register_param.port
    service = register_param.service_name
    group = register_param.group_name
    probe_url = f"http://{settings.NACOS_SERVER}/nacos/v1/ns/instance/list"
    probe_params = {"serviceName": service, "groupName": group, "healthyOnly": "true"}
    fail_streak = 0

    async with httpx.AsyncClient(timeout=5.0) as http:
        while True:
            await asyncio.sleep(_NACOS_KEEPALIVE_INTERVAL)
            try:
                resp = await http.get(probe_url, params=probe_params)
                resp.raise_for_status()
                hosts = resp.json().get("hosts") or []
                alive = any(
                    h.get("ip") == ip and int(h.get("port") or 0) == port and h.get("healthy", True)
                    for h in hosts
                )
                if alive:
                    if fail_streak:
                        print(f"✅ Nacos 实例已恢复在册: {ip}:{port}", flush=True)
                    fail_streak = 0
                    continue

                # 实例不在册 → 补注册
                fail_streak += 1
                print(f"⚠️ Nacos 实例不在册，补注册（第 {fail_streak} 次）: {ip}:{port}", flush=True)
                try:
                    await get_client().register_instance(request=register_param)
                    print("✅ Nacos 补注册成功", flush=True)
                    fail_streak = 0
                except Exception as e:
                    print(f"Nacos 补注册失败: {e}", flush=True)
                    if fail_streak >= _NACOS_REBUILD_AFTER:
                        print(f"补注册连续失败 {fail_streak} 次，重建 Nacos gRPC 客户端", flush=True)
                        try:
                            new_client = await get_nacos_service()
                            set_client(new_client)
                            await new_client.register_instance(request=register_param)
                            print("✅ Nacos 客户端重建并注册成功", flush=True)
                            fail_streak = 0
                        except Exception as e2:
                            print(f"Nacos 客户端重建失败: {e2}", flush=True)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                # 探测本身失败（Nacos 短暂不可用）不应中断守护循环
                print(f"Nacos 保活探测异常: {e}", flush=True)


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
    local_ip = get_local_ip()

    # ============================== nacos配置拉取 ==================================
    #拉取配置
    nacos_config_client = await get_nacos_config()

    # 构建配置参数，data_id 和 group 需与 Nacos 控制台一致
    config_param = ConfigParam(
        data_id="agent.yaml",
        group="LIFE_MIND_CONFIG"
    )

    remote_config = await nacos_config_client.get_config(config_param)
    if remote_config:
        try:
            nacos_agent_config = yaml.safe_load(remote_config)
            for key, value in nacos_agent_config.items():
                upper = str(key).upper()
                if hasattr(settings, upper):
                    setattr(settings, upper, value)

            print("从nacos拉取agent服务配置成功")

        except yaml.YAMLError as e:
            print(f"❌ YAML 格式错误，解析失败: {e}")
    else:
        print("⚠️ Nacos 上没找到该配置文件，将使用默认值")

    #构建监听器，动态更新配置
    await nacos_config_client.add_listener(
        data_id="agent.yaml",
        group="LIFE_MIND_CONFIG",
        listener=on_config_changed
    )

    # ============================= nacos服务注册 =================================
    #注册服务
    nacos_name_client = await get_nacos_service()

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

    # 客户端引用放到容器里，保活任务重建客户端后能写回，注销时用最新的客户端
    client_ref = {"client": nacos_name_client}

    def _get_client():
        return client_ref["client"]

    def _set_client(new_client):
        client_ref["client"] = new_client

    # ====================== nacos 注册保活兜底任务 ======================
    # SDK 的 gRPC 长连接断开后不自动重注册，独立 HTTP 探测兜底，避免网关路由不到 agent
    keepalive_task: Optional[asyncio.Task] = asyncio.create_task(
        _nacos_register_keepalive(logger, _get_client, _set_client, register_param)
    )
    print(f"🔄 Nacos 注册保活任务已启动（间隔 {_NACOS_KEEPALIVE_INTERVAL}s）", flush=True)

    # ========================= 初始化mysql数据库连接 ==============================
    init_mysql_db()

    # ===================== 初始化postgresql数据库的连接 ===========================
    await connect_postgresql_db()

    # =========================== 初始化milvus客户端 ==============================
    init_milvus_client()

    # ========== 服务运行 ==========
    # 卡住进程，以此来控制一些组件的连接的生命周期
    yield

    logger.info("🔌 正在关闭系统依赖...")

    # 先停保活任务，避免注销后又被补注册
    if keepalive_task:
        keepalive_task.cancel()
        try:
            await keepalive_task
        except asyncio.CancelledError:
            pass

    # ======================= 注销nacos注册的服务 ===============================
    try:
        await client_ref["client"].deregister_instance(DeregisterInstanceParam(
            service_name=settings.APP_NAME,
            group_name=settings.NACOS_GROUP,
            ip=local_ip,
            port=settings.SERVER_PORT
        ))
        logger.info("✅ Nacos 服务已注销")
    except Exception as e:
        logger.warning("Nacos 注销失败（进程即将退出，可忽略）: %s", e)

    # ========================关闭postgresql连接 =================================
    await close_postgresql_db()

    # ========================关闭下游服务连接池 =================================
    await close_client()
