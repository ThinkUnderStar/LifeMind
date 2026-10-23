import logging

import yaml
from v2.nacos import ClientConfigBuilder, NacosNamingService, NacosConfigService

from app.config.settings import settings

# 构建连接 Nacos 服务端的客户端对象
# 连接nacos服务的配置
client_config = (
    ClientConfigBuilder()
    .server_address(settings.NACOS_SERVER)
    .namespace_id(settings.NACOS_NAMESPACE)
    .build()
)


# 获取服务发现客户端
async def get_nacos_service() -> NacosNamingService:
    """
    获取nacos服务的连接对象

    :return: nacos的服务连接对象
    """
    return await NacosNamingService.create_naming_service(client_config)


# 获取配置拉取客户端
async def get_nacos_config() -> NacosConfigService:
    """
    获取nacos的配置连接对象

    :return: nacos的配置连接对象
    """
    return await NacosConfigService.create_config_service(client_config)


# 解析并覆盖配置
def apply_remote_config(config: str):
    """
    解析并覆盖配置

    :param config: 拉取的配置
    :return: None
    """
    logger = logging.getLogger(__name__)

    if config:
        try:
            remote_config = yaml.safe_load(config)
            for key, value in remote_config.items():
                upper = str(key).upper()
                if hasattr(settings, upper):
                    setattr(settings, upper, value)

            logger.info(f"✅ Nacos 配置覆盖成功！共更新了 {len(remote_config)} 个字段: {remote_config}")

        except yaml.YAMLError as e:
            print(f"❌ YAML 格式错误，解析失败: {e}")
    else:
        print("⚠️ Nacos 上没找到该配置文件，将使用默认值")


# 监听器执行逻辑
def on_config_changed(config):
    """这是当 Nacos 配置变动时，SDK 自动触发的回调函数"""
    print(f"\n🔄 检测到 Nacos 配置变更！最新内容:\n{config['content']}")
    # 在回调里再次调用覆盖方法
    apply_remote_config(config['content'])