"""agent 的启动入口（Windows 专用注意事项）。

不能直接用 `uvicorn main:app`：Windows 上 asyncio 默认用 ProactorEventLoop，
而 psycopg3 的异步连接明确拒绝它，启动时 langgraph 建 checkpoint saver 就会抛：

    psycopg.InterfaceError: Psycopg cannot use the 'ProactorEventLoop' to run in
    async mode.

这个限制发生在 connect 阶段，lifespan 里再补设置已经晚了——循环已经建好。
而 uvicorn 的命令行没有暴露事件循环的创建方式，所以这里自己构造 Server，
用 loop_factory 指定 SelectorEventLoop。Python 3.12+ 的 asyncio.run 支持该参数。

用法：
    ./.venv/Scripts/python.exe run.py
"""

import asyncio
import selectors
import sys

import uvicorn

HOST = "0.0.0.0"
PORT = 8004


def main() -> None:
    config = uvicorn.Config("main:app", host=HOST, port=PORT, log_level="info")
    server = uvicorn.Server(config)

    if sys.platform == "win32":
        # SelectSelector 在 Windows 上走 select()，能处理 socket，psycopg 才能用
        asyncio.run(
            server.serve(),
            loop_factory=lambda: asyncio.SelectorEventLoop(selectors.SelectSelector()),
        )
    else:
        asyncio.run(server.serve())


if __name__ == "__main__":
    main()
