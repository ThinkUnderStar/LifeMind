from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    应用全局配置管理类。

    继承自 Pydantic 的 BaseSettings，具备自动从环境变量和 .env 文件中
    读取配置并覆盖默认值的能力，同时自带严格的类型校验。
    """
    # =================== server 相关配置参数 ======================
    APP_NAME: str = "life-mind-agent"
    SERVER_PORT: int = 8004

    # =================== nacos 服务配置参数 =======================
    NACOS_SERVER: str = "127.0.0.1:8848"
    NACOS_NAMESPACE: str = ""
    NACOS_GROUP: str = "DEFAULT_GROUP"

    # ==================== Pydantic 配置加载规则 ====================
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore"
    )

#创建全局配置类对象
settings = Settings()
