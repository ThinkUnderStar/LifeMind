import logging

from fastapi import FastAPI, Request, Response, status
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import JSONResponse

from app.exception.exceptions import BusinessException
from app.schemas.web import Result

# 打印日志用
logger = logging.getLogger(__name__)


def setup_global_exceptions_handlers(app: FastAPI) -> None:
    """
    注册全局异常处理器。

    将系统内不同层级的异常（业务异常、HTTP异常、参数校验异常、未知系统异常）
    统一捕获并转换为规范的 JSON 响应格式（Result），返回给客户端或网关。

    :param app: FastAPI 应用实例。通过参数注入的方式传入，避免在模块顶层
                直接导入 app 导致的循环依赖（Circular Import）问题。
    :return: 无返回值（None），直接在传入的 app 实例上完成装饰器注册。
    """

    # 1. 捕获：自定义业务异常
    @app.exception_handler(BusinessException)
    async def business_exception_handler(request: Request, exc: BusinessException) -> Response:
        logger.warning(f"业务异常拦截: {request.method} {request.url.path} - {exc.message}")
        return JSONResponse(
            status_code=exc.code,
            content=Result.error(code=exc.code, message=exc.message).model_dump(),
        )

    # 2. 捕获：FastAPI/Starlette 内置的 HTTP 异常 (如 404 找不到、401 未授权)
    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> Response:
        logger.warning(f"HTTP异常: {request.method} {request.url.path} - {exc.detail}")
        return JSONResponse(
            status_code=exc.status_code,
            content=Result.error(code=exc.status_code, message=str(exc.detail)).model_dump(),
        )

    # 3. 捕获：Pydantic 参数校验异常 (如前端传的 JSON 缺少字段、类型不对)
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> Response:
        # 提取第一个错误信息，方便前端展示
        error_msg = exc.errors()[0].get("msg", "参数校验失败") if exc.errors() else "参数校验失败"
        logger.warning(f"参数校验异常: {request.method} {request.url.path} - {error_msg}")

        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content=Result.error(
                code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                message=f"请求参数错误: {error_msg}"
            ).model_dump(),
        )

    # 4. 兜底：捕获所有未知的系统异常 (如大模型断网、数据库挂掉、空指针)
    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception) -> Response:
        # 必须记录完整堆栈 (exc_info=exc)，方便线上排错
        logger.error(f"系统全局异常: {request.method} {request.url.path}", exc_info=exc)

        # 隐藏内部错误细节，统一返回 500
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content=Result.error(
                code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                message="服务器内部异常，请稍后再试"
            ).model_dump(),
        )