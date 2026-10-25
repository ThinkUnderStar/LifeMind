from fastapi import Header, HTTPException

async def get_current_user_id(
    x_user_id: str = Header(None, alias="LifeMind-User-Id")
) -> int:
    """从请求头提取并校验用户 ID"""
    if not x_user_id:
        raise HTTPException(status_code=401, detail="缺少用户身份标识")
    try:
        return int(x_user_id)
    except ValueError:
        raise HTTPException(status_code=400, detail="用户 ID 格式错误")