from fastapi import FastAPI

from app.api.v1.router import api_v1_router
from app.core.settings import settings
from app.core.lifespan import lifespan

app = FastAPI(lifespan=lifespan)

#注册路由接口
app.include_router(api_v1_router, prefix=settings.SERVER_PATH)
