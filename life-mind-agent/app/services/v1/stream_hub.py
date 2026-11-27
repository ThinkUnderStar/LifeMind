"""按会话缓存一轮对话产生的 SSE 帧，供断线重连补发。

为什么要这一层：原来图的执行挂在 SSE 响应生成器上，浏览器一刷新，生成器被取消，
图就停在当前节点、AI 回复永远不落库。现在把两者拆开——

- 后台任务负责跑图，把每一帧写进本会话的 `StreamSession`；
- SSE 响应只从 `StreamSession` 读帧发给浏览器。连接断了后台照跑，回复照常落库。

帧编号就是 `frames` 列表下标（从 0 开始）。重连时前端带 after_seq（已收到的最后一帧
下标），后端从 after_seq+1 补发，已显示的帧不会重复。
"""
import asyncio
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional

# 一轮结束后缓存保留多久（秒）。留着是为了晚一点的重连还能拿到完整帧；
# 超时就丢，防止一个用户反复对话时内存无限涨。
TTL_SECONDS = 10 * 60


@dataclass
class StreamSession:
    """一轮对话产生的帧序列。"""

    session_id: int
    frames: List[str] = field(default_factory=list)
    done: bool = False
    changed: asyncio.Event = field(default_factory=asyncio.Event)
    finished_at: Optional[float] = None

    def publish(self, frame: str) -> None:
        self.frames.append(frame)
        self.changed.set()

    def finish(self) -> None:
        self.done = True
        if self.finished_at is None:
            self.finished_at = time.monotonic()
        self.changed.set()

    def expired(self) -> bool:
        # 从结束时刻开始计时：跑得久的轮次不能因为总时长超了就在半途被清掉
        return (self.done and self.finished_at is not None
                and time.monotonic() - self.finished_at > TTL_SECONDS)


_sessions: Dict[int, StreamSession] = {}


def _sweep() -> None:
    """顺手清掉过期的旧会话。每次 create/get 都调，不用另起定时任务。"""
    for sid in [sid for sid, s in _sessions.items() if s.expired()]:
        _sessions.pop(sid, None)


def create(session_id: int) -> StreamSession:
    """开始新一轮：替换该会话的旧条目。

    旧后台任务仍持有旧对象继续写，写不到新流里——`resume` 从确认处恢复时
    新旧两轮短暂并存，这样不会串帧。
    """
    _sweep()
    session = StreamSession(session_id=session_id)
    _sessions[session_id] = session
    return session


def get(session_id: int) -> Optional[StreamSession]:
    _sweep()
    return _sessions.get(session_id)


def discard(session_id: int) -> None:
    _sessions.pop(session_id, None)
