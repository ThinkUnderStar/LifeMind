from fastapi import FastAPI

from app.api.v1.router import api_v1_router
from app.core.settings import settings
from app.core.lifespan import lifespan
from app.exception.global_handlers import setup_global_exceptions_handlers

app = FastAPI(lifespan=lifespan)

# 注册路由接口
app.include_router(api_v1_router, prefix=settings.SERVER_PATH)

# 注册全局异常处理器
setup_global_exceptions_handlers(app)
