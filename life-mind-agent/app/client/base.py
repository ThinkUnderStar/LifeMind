"""
组件服务调用的公共基座：连接复用、身份头注入、统一响应解包、统一错误表达。

三条约定值得单独说明：

1. **失败一律抛 `ServiceError`，且 message 是能直接给模型看的中文。**
   工具层捕获后原样回填给模型，模型据此判断是「参数错了要改」还是
   「服务挂了要告诉用户」。如果这里只抛 httpx 的原始异常，
   模型拿到的是 `ConnectError: [Errno 111]` 这种没法处理的东西。

2. **HTTP 状态码与 body 里的 code 都要看。**
   下游服务的业务异常会同时给出非 2xx 状态和 `Result.code=4xx`，
   但也有只在 body 里表达失败的情况（历史接口全 200）。两个都判，
   避免把「事项不存在」当成成功去回填一个空 data。

3. **连接复用而不是每次新建。**
   httpx 的 AsyncClient 带连接池，跨请求复用能省掉 TCP/TLS 握手。
   进程内一个实例足够：FastAPI 是单事件循环，且这里不做并发隔离。
"""
import logging
from typing import Any, Dict, Optional, Union

import httpx

logger = logging.getLogger(__name__)

# 建连超时与读超时分开：连不上要快速失败（而不是卡满整个读超时），
# 读要留够时间给下游的数据库查询。
CONNECT_TIMEOUT = 3.0
READ_TIMEOUT = 8.0

# 下游 Result.code 的成功值，与 schedule/account/message 三家的 ResultCode.SUCCESS 一致
SUCCESS_CODE = 200

# 进程内共享的连接池，惰性创建
_client: Optional[httpx.AsyncClient] = None


class ServiceError(Exception):
    """
    组件服务调用失败。

    message 面向模型/用户，不含堆栈；排查细节通过 status_code / code / 日志拿到。
    刻意不继承项目里的 BusinessException：那个是「本次请求本身不合法」，
    会被全局异常处理器转成 4xx 返回给前端；而这里的失败是「agent 内部调不通下游」，
    应该被工具层吃掉、变成一句可读的工具返回值，不能让整轮对话 500。
    """

    def __init__(
            self,
            message: str,
            *,
            service_name: str = "",
            status_code: Optional[int] = None,
            code: Optional[int] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.service_name = service_name
        self.status_code = status_code
        self.code = code

    def __str__(self) -> str:
        return self.message


def _get_client() -> httpx.AsyncClient:
    """
    取共享的 HTTP 客户端，没有就建一个。

    不加锁：FastAPI 单事件循环，且 httpx 客户端在创建后是并发安全的；
    极端情况下并发首次请求各建一个，只是多一个连接池，不会出错。
    """
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(READ_TIMEOUT, connect=CONNECT_TIMEOUT),
            # 不跟随重定向：下游都是内部服务，出现 3xx 说明配置错了，
            # 跟着跳反而会打到一个意料之外的地址
            follow_redirects=False,
        )
    return _client


async def close_client() -> None:
    """
    关闭共享连接池。由应用关闭时调用，避免长连接挂到进程退出。
    """
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None


def build_headers(user_id: Union[int, str]) -> Dict[str, str]:
    """
    构造调用下游服务需要的身份头。

    下游的 `HeaderAuthenticationFilter` 要求三个头齐全，缺一个就 401：
    - `LifeMind-User-Id`：谁的数据，进入业务层的唯一归属依据；
    - `LifeMind-User-Roles` / `LifeMind-User-Scopes`：只用来构建 Authentication。

    roles/scopes 这里给固定最小集，而不是从网关请求头透传，原因是：
    下游目前没有启用方法级安全，这两个值不参与任何判定，纯粹是过滤器要求存在。
    若将来下游启用 `@EnableMethodSecurity`（例如「只允许 admin 看全部」），
    必须改成从网关注入的原始头透传，否则等于 agent 自动获得 user/read 权限。
    """
    return {
        "LifeMind-User-Id": str(user_id),
        "LifeMind-User-Roles": "user",
        "LifeMind-User-Scopes": "read",
        # 落库标记数据来源：schedule_item.source 据此区分「agent 代操作」和「用户手写」
        "LifeMind-Client": "agent",
    }


async def request_json(
        *,
        service_name: str,
        base_url: str,
        method: str,
        path: str,
        user_id: Union[int, str],
        params: Optional[Dict[str, Any]] = None,
        body: Optional[Dict[str, Any]] = None,
) -> Any:
    """
    发一次请求并返回 `Result.data`。

    :param service_name: 服务的中文名，用在错误提示里（「日程服务暂时不可用」）
    :param base_url: 服务根地址，含 context-path，不带结尾斜杠
    :param method: HTTP 方法，大写
    :param path: 以 / 开头的接口路径
    :param user_id: 当前用户ID，作为归属依据注入请求头
    :param params: 查询参数，None 值会被剔除（避免拼出 `?status=None`）
    :param body: 请求体，按 JSON 发送
    :return: 下游 `Result.data`，可能为 None
    :raises ServiceError: 连不通、超时、非 2xx、业务码非 200
    """
    url = f"{base_url.rstrip('/')}{path}"
    # None 值剔除：httpx 会把 None 序列化成字符串 "None"，下游收到就是非法值
    clean_params = {k: v for k, v in (params or {}).items() if v is not None} or None

    try:
        response = await _get_client().request(
            method=method,
            url=url,
            params=clean_params,
            json=body,
            headers=build_headers(user_id),
        )
    except httpx.TimeoutException:
        logger.error("%s 调用超时：%s %s", service_name, method, url)
        raise ServiceError(f"{service_name}响应超时，请稍后重试", service_name=service_name)
    except httpx.HTTPError as e:
        logger.error("%s 调用失败：%s %s -> %s", service_name, method, url, e)
        raise ServiceError(f"{service_name}暂时连不上，请确认服务是否已启动", service_name=service_name)

    # 下游的业务异常也会带非 2xx（GlobalExceptionHandler 映射过），
    # 此时 body 里仍是一份 Result，优先取它的 message，比「HTTP 404」有用得多
    payload = _parse_body(response, service_name)

    if response.status_code >= 400:
        message = _message_of(payload) or f"{service_name}返回异常状态 {response.status_code}"
        logger.warning("%s 返回 %s：%s %s -> %s", service_name, response.status_code, method, path, message)
        raise ServiceError(message, service_name=service_name, status_code=response.status_code)

    code = payload.get("code") if isinstance(payload, dict) else None
    if code is not None and int(code) != SUCCESS_CODE:
        message = _message_of(payload) or f"{service_name}处理失败（code={code}）"
        logger.warning("%s 业务失败：%s %s -> %s", service_name, method, path, message)
        raise ServiceError(message, service_name=service_name, status_code=response.status_code, code=int(code))

    return payload.get("data") if isinstance(payload, dict) else None


def _parse_body(response: httpx.Response, service_name: str) -> Any:
    """
    解析响应体。解析不出来时不抛异常，返回 None——错误信息由调用方按状态码补。
    """
    if not response.content:
        return None
    try:
        return response.json()
    except ValueError:
        logger.warning("%s 返回了非 JSON 响应：status=%s body=%s",
                       service_name, response.status_code, response.text[:200])
        return None


def _message_of(payload: Any) -> str:
    """
    从 `Result` 里取 message，取不到返回空串。
    """
    if isinstance(payload, dict):
        message = payload.get("message")
        if isinstance(message, str):
            return message.strip()
    return ""
