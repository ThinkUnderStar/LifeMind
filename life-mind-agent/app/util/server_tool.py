import socket

def get_local_ip() -> str:
    """获取本机局域网 IP（如果是 Docker 部署，建议改为读取环境变量）"""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"
