import time
from enum import Enum
from typing import Generic, Optional, TypeVar, Union
from pydantic import BaseModel, ConfigDict, Field


class ResultCode(Enum):
    SUCCESS = (200, "操作成功")
    ERROR = (500, "操作失败")
    PARAM_ERROR = (400, "参数校验失败")
    UNAUTHORIZED = (401, "未登录或登录已过期")
    FORBIDDEN = (403, "没有操作权限")
    NOT_FOUND = (404, "请求的资源不存在")
    TOO_MANY_REQUESTS = (429, "操作过于频繁，请稍后再试")

    @property
    def code(self) -> int:
        return self.value[0]

    @property
    def message(self) -> str:
        return self.value[1]


T = TypeVar("T")


class Result(BaseModel, Generic[T]):
    model_config = ConfigDict(arbitrary_types_allowed=True)

    code: int = Field(default=ResultCode.SUCCESS.code)
    message: str = Field(default=ResultCode.SUCCESS.message)
    data: Optional[T] = Field(default=None)
    timestamp: int = Field(default_factory=lambda: int(time.time() * 1000))

    @classmethod
    def success(cls, data: Optional[T] = None, message: str = ResultCode.SUCCESS.message) -> "Result[T]":
        """成功返回。支持 Result.success(data) 或 Result.success(data=..., message=...)"""
        return cls(code=ResultCode.SUCCESS.code, message=message, data=data)

    @classmethod
    def error(cls, code: Union[int, ResultCode] = ResultCode.ERROR, message: Optional[str] = None) -> "Result[T]":
        """
        失败返回。
        支持：
        - Result.error() -> 默认 500
        - Result.error(code=403, message="无权限")
        - Result.error(ResultCode.FORBIDDEN)
        """
        if isinstance(code, ResultCode):
            final_code = code.code
            final_msg = message if message is not None else code.message
        else:
            final_code = int(code)
            final_msg = message if message is not None else ResultCode.ERROR.message

        return cls(code=final_code, message=final_msg, data=None)