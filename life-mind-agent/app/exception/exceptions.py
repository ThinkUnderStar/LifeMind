class BusinessException(Exception):
    """
    自定义业务异常类。
    用于处理可预期的、需要明确提示给用户的错误。
    """
    def __init__(self, message: str, code: int = 400):
        self.message = message
        self.code = code

        # 底层打印日志或获取报错信息用
        super().__init__(self.message)