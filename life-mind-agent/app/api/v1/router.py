from fastapi import APIRouter

from app.api.v1.chat_router import chat_router
from app.api.v1.session_router import session_router

api_v1_router = APIRouter(prefix="/v1")

# 注册agent路由
api_v1_router.include_router(chat_router)
# 注册会话管理路由
api_v1_router.include_router(session_router)
