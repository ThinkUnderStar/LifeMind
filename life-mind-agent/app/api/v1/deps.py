"""路由层公共依赖。

身份口径与网关一致：只认 LifeMind-User-Id 请求头，缺失即 401、非数字即 400，
不接受请求体里的 user_id，也不做「默认用户」兜底。

抽成依赖而不是每个路由手写一遍：多一个接口就多一次复制，
而这类校验一旦某处漏写，就是越权读别人的会话。
"""
from typing import Optional

from fastapi import Depends, Header

from app.client.base import Identity
from app.exception.exceptions import BusinessException


async def current_user_id(
        user_id: Optional[str] = Header(default=None, alias="LifeMind-User-Id"),
) -> int:
    """
    取当前登录用户ID。

    :param user_id: 网关校验 JWT 后注入的请求头
    :return: 整数形式的用户ID
    :raises BusinessException: 缺失 401、格式非法 400
    """
    if not user_id:
        raise BusinessException(message="缺少用户身份信息", code=401)

    text = str(user_id).strip()
    if not text.isdigit():
        raise BusinessException(message="用户身份格式非法", code=400)

    return int(text)


async def current_identity(
        user_id: int = Depends(current_user_id),
        roles: Optional[str] = Header(default=None, alias="LifeMind-User-Roles"),
        scopes: Optional[str] = Header(default=None, alias="LifeMind-User-Scopes"),
) -> Identity:
    """
    取当前用户完整身份：ID + 角色 + 权限范围。

    前两个头由网关校验 JWT 后注入，agent 只透传、不解析、不扩大：
    下游服务用它们构建 Authentication，将来启用方法级安全时决定能做什么。

    roles/scopes 缺失不报错，由 `Identity.of` 降权到 user/read——
    直连 agent 调试时本来就没有这两个头，此时拒绝整个请求属过度反应；
    但降权方向只能是「更小」，不能是「更大」。
    """
    return Identity.of(user_id, roles=roles, scopes=scopes)


async def current_plugins(
        plugins: Optional[str] = Header(default=None, alias="LifeMind-User-Plugins"),
) -> str:
    """
    取当前用户启用的插件标识串。

    这个请求头由网关在 Redis 查不到时回源 account 服务后注入，格式是逗号分隔的
    plugin_key（如 `schedule`）。**它不参与鉴权，缺失也不报错**：
    插件是增强能力，没有插件时 agent 只绑定内置工具，对话仍可正常进行。

    这里只做清洗（去空白），不校验每个 key 是否存在：
    是否存在由数据层（plugin 表）保证，agent 侧多一层校验反而会出现
    「一边放行一边拒绝」的不一致。

    :param plugins: 网关注入的插件标识串，可能为 None（老网关或直连 agent）
    :return: 清洗后的插件标识串，缺失时为空串
    """
    return (plugins or "").strip()
