from fastapi import APIRouter

from app.config.settings import settings

chat_router = APIRouter(prefix="/chat", tags=["Agent"])

