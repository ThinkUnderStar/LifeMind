from fastapi import APIRouter

from app.api.v1.chat_router import chat_router

api_v1_router = APIRouter(prefix="/v1")

# 注册agent路由
api_v1_router.include_router(chat_router)
