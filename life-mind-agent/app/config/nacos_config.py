from v2.nacos import ClientConfigBuilder

from app.config.settings import settings

# 构建连接 Nacos 服务端的客户端对象
# 连接nacos服务的配置
client_config = (
    ClientConfigBuilder()
    .server_address(settings.NACOS_SERVER)
    .namespace_id(settings.NACOS_NAMESPACE)
    .build()
)