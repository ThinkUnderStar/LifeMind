"""
组件服务工具：日程插件（plugin_key = schedule）。

与 `agent_tools.py` 的区别：那里的工具是 agent 自己的能力（记忆、任务、联网），
任何用户都有；这里的每个工具都归属某个插件，只有用户启用该插件时才会被绑定进模型。

## 命名约定

工具名统一为「插件key_动作」，插件 key 与 account 库 `plugin.plugin_key` 一致。
`service_catalog` 在导入时会校验这个前缀，写错了启动即报错，不会等到用户点了插件才发现。

## 敏感标记

这里反过来标：**只读工具登记在 `READONLY_TOOL_NAMES` 里免确认，其余一律敏感**。
默认值选「敏感」而不是「不敏感」，是因为漏标注的后果不对称——
漏标一个只读工具只是多弹一次确认，漏标一个写操作就是模型可以不经用户同意
直接删掉一条日程。

敏感工具在 `approval_gate` 里会触发 interrupt，用户 approve 才执行；
权限为 full 的用户不弹确认，直接执行（口径见 nodes.py 的 approval_gate）。

## 时间参数

下游服务能识别多种写法（`2026-11-21 09:30`、`2026-11-21T09:30:00`、`2026-11-21`），
但**「明天下午三点」这种自然语言不行**。模型必须先把相对时间换算成绝对时间再传，
换算依据是系统提示词里给出的当前时间。
"""
import logging
from typing import Any, Dict, FrozenSet, List, Literal, Optional

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from app.client.base import Identity, ServiceError
from app.client.schedule_client import schedule_service

logger = logging.getLogger(__name__)

# 下游的状态/类型/优先级取值，格式化结果时用
STATUS_TEXT = {0: "未完成", 1: "已完成", 2: "已取消"}
TYPE_TEXT = {"schedule": "日程", "todo": "待办"}
PRIORITY_TEXT = {0: "低", 1: "中", 2: "高"}

# 重复规则的展示用词：频率的单位 + 星期几的中文
FREQ_UNIT_TEXT = {"DAILY": "天", "WEEKLY": "周", "MONTHLY": "月"}
DAY_TEXT = {"MO": "周一", "TU": "周二", "WE": "周三", "TH": "周四",
            "FR": "周五", "SA": "周六", "SU": "周日"}

# 一次最多返回多少条：服务端上限 100，这里压到 50——
# 工具结果是直接进模型上下文的，100 条格式化后能吃掉近万 token
MAX_PAGE_SIZE = 50

# 取不到身份时的统一回复：身份只认运行时 config，绝不做「默认用户」兜底
NO_IDENTITY = "未取到当前用户身份，这条操作没有执行。"

# 只读工具（免人工确认）：列表、详情、统计、提醒查询。
# 新增只读工具时必须登记在这里，否则会按敏感处理——宁可多确认一次，不可少确认一次。
READONLY_TOOL_NAMES: FrozenSet[str] = frozenset({
    "schedule_get_item",
    "schedule_list_items",
    "schedule_list_series",
    "schedule_overview",
    "schedule_daily_stats",
    "schedule_upcoming_reminders",
    "schedule_memo_get",
    "schedule_memo_list",
})


# ======================== 内部工具 ========================

def _identity(config: RunnableConfig) -> Optional[Identity]:
    """
    从运行时 config 取用户身份三元组（ID + 角色 + 范围）。

    与内置工具同一口径：只认 `configurable.user_id`（网关校验 JWT 后注入），
    缺失就放弃这次调用，绝不回退到某个默认用户——那会往别人的数据里写东西。
    这里不做 int 转换失败的重试，格式不对说明上游写错了，记日志即可。

    roles/scopes 与 user_id 同源，都是网关校验 JWT 后注入的原始头，
    由 chat 层放进 configurable。它们决定 agent 调下游时能做什么，
    因此不能写死——写死等于 agent 用一个固定权限的账号代所有人操作。
    """
    configurable = config.get("configurable", {})
    raw = configurable.get("user_id")
    if raw is None or raw == "":
        logger.error("服务工具缺少 user_id，已跳过：configurable=%s", configurable)
        return None
    try:
        return Identity.of(
            raw,
            roles=configurable.get("user_roles"),
            scopes=configurable.get("user_scopes"),
        )
    except (TypeError, ValueError):
        logger.error("服务工具的 user_id 不是整数：%r", raw)
        return None


def _time_range(item: Dict[str, Any]) -> str:
    """
    把一条事项的时间压成一段可读文本，日程看起止、待办看截止。
    """
    if item.get("itemType") == "schedule":
        start = item.get("startTime") or ""
        end = item.get("endTime")
        return f"{start}~{end}" if end else start
    due = item.get("dueTime")
    return f"截止 {due}" if due else "无截止时间"


def _fmt_item(item: Dict[str, Any]) -> str:
    """
    单条事项 → 一行文本。

    故意不返回 JSON：模型对 JSON 里几十个字段（含 createTime/updateTime/source）
    没有分辨力，反而容易把 requestId 之类的内部字段写进回复里。只留用户关心的。
    """
    if not item:
        return "（空）"

    parts = [
        f"id={item.get('id')}",
        f"[{TYPE_TEXT.get(item.get('itemType'), item.get('itemType'))}]",
        item.get("title") or "(无标题)",
        _time_range(item),
    ]
    if item.get("location"):
        parts.append(f"@{item.get('location')}")
    if item.get("priority") is not None:
        parts.append(f"优先级{PRIORITY_TEXT.get(item.get('priority'), item.get('priority'))}")
    status = item.get("status")
    parts.append(STATUS_TEXT.get(status, f"状态{status}") if status is not None else "状态未知")

    offsets = item.get("remindOffsets")
    if offsets:
        parts.append("提醒：" + "、".join(f"提前{int(o)}分钟" if int(o) > 0 else "准时" for o in offsets))

    line = " ".join(str(p) for p in parts)
    if item.get("content"):
        line += f"\n    内容：{item.get('content')}"
    return line


async def _detail_after_write(identity: Identity, item_id: int) -> str:
    """
    写操作成功后回查一次详情。

    complete / cancel 接口返回的是 `Result<Void>`——下游只报「改成功了」，不带改后的样子。
    模型需要当前状态才能如实回话（「已标记完成，状态：已完成」），所以补一次读。
    回查失败不影响结论：写已经成功了，只是这句描述不够具体。
    """
    try:
        item = await schedule_service.get_item(identity, item_id)
    except ServiceError:
        logger.warning("写操作后回查事项失败：user_id=%s item_id=%s", identity.user_id, item_id)
        return f"id={item_id}（操作已执行，但当前状态查询失败）"
    return _fmt_item(item)


def _fmt_page(page: Dict[str, Any]) -> str:
    """
    分页结果 → 头部一行 + 每条一行。

    显式写出 total：模型经常把「这一页 20 条」当成「一共 20 条」，
    进而对用户说「你有 20 个待办」，实际有 80 个。
    """
    records = (page or {}).get("records") or []
    total = (page or {}).get("total")
    if not records:
        return "没有找到符合条件的事项。"

    lines = [f"共 {total} 条，本页 {len(records)} 条："]
    lines.extend(f"- {_fmt_item(item)}" for item in records)
    return "\n".join(lines)


def _fmt_memo(memo: Dict[str, Any]) -> str:
    """单条备忘录 → 一行文本。"""
    if not memo:
        return "（空）"
    title = memo.get("title") or "(无标题)"
    line = f"id={memo.get('id')} [备忘录] {title}"
    if memo.get("pinned") == 1:
        line += " 置顶"
    if memo.get("tags"):
        line += f" 标签={memo.get('tags')}"
    line += f"\n    内容：{memo.get('content')}"
    return line


def _fmt_series(series: Dict[str, Any]) -> str:
    """
    单条重复计划 → 一行文本。

    规则部分要说人话：「每 2 周的 周一、周三」比 FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE
    更容易被模型转述成用户听得懂的一句话。
    """
    if not series:
        return "（空）"

    freq = series.get("freq")
    unit = FREQ_UNIT_TEXT.get(freq, freq or "?")
    interval = series.get("interval") or 1
    rule = f"每{unit}" if interval == 1 else f"每{interval}{unit}"

    days = series.get("byDay") or []
    if days:
        rule += "的 " + "、".join(DAY_TEXT.get(d, d) for d in days)

    parts = [
        f"id={series.get('id')}",
        f"[{TYPE_TEXT.get(series.get('itemType'), series.get('itemType'))}]",
        series.get("title") or "(无标题)",
        rule,
        f"锚点 {series.get('baseTime')}",
    ]
    if series.get("durationMinutes"):
        parts.append(f"每次{series.get('durationMinutes')}分钟")
    if series.get("untilDate"):
        parts.append(f"至 {series.get('untilDate')}")
    if series.get("countLimit"):
        parts.append(f"共 {series.get('countLimit')} 次")
    if series.get("itemCount") is not None:
        parts.append(f"已展开 {series.get('itemCount')} 条")
    if series.get("firstItemId"):
        parts.append(f"首次 id={series.get('firstItemId')}")
    if series.get("lastItemId"):
        parts.append(f"末次 id={series.get('lastItemId')}")
    if series.get("status") == 1:
        parts.append("已结束")

    offsets = series.get("remindOffsets")
    if offsets:
        parts.append("提醒：" + "、".join(
            f"提前{int(o)}分钟" if int(o) > 0 else "准时" for o in offsets))

    line = " ".join(str(p) for p in parts)
    if series.get("content"):
        line += f"\n    内容：{series.get('content')}"
    return line


def _fmt_batch(result: Dict[str, Any], action: str) -> str:
    """
    批量操作结果 → 可读文本。

    逐条回报是服务端的设计（一条失败不回滚已成功的），这里必须把失败原因带上，
    否则模型只能对用户说「部分失败」，等于没说。
    """
    succeeded = (result or {}).get("succeeded") or []
    failed = (result or {}).get("failed") or []

    lines = [f"{action}：成功 {len(succeeded)} 条" + (f"（id={','.join(str(i) for i in succeeded)}）" if succeeded else "")]
    if failed:
        lines.append("失败 " + str(len(failed)) + " 条：")
        lines.extend(f"- id={f.get('id')}：{f.get('reason')}" for f in failed)
    return "\n".join(lines)


def _build_item_body(
        *,
        item_type: Optional[str] = None,
        title: Optional[str] = None,
        content: Optional[str] = None,
        location: Optional[str] = None,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        due_time: Optional[str] = None,
        priority: Optional[int] = None,
        remind_offsets: Optional[List[int]] = None,
) -> Dict[str, Any]:
    """
    组装下游请求体：只放有值的字段。

    None 必须被剔除而不是发 null：下游是「null = 不改」的部分更新语义，
    发 null 等于什么都没做；而创建时发 null 又会被当成显式空值。
    两边语义不同，统一「没值就不发」最省心。
    """
    mapping = {
        "itemType": item_type,
        "title": title,
        "content": content,
        "location": location,
        "startTime": start_time,
        "endTime": end_time,
        "dueTime": due_time,
        "priority": priority,
        "remindOffsets": remind_offsets,
    }
    return {k: v for k, v in mapping.items() if v is not None}


# ======================== 事项：写操作（敏感） ========================

@tool(name_or_callable="schedule_create_item")
async def schedule_create_item(
        config: RunnableConfig,
        item_type: Literal["schedule", "todo"],
        title: str,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        due_time: Optional[str] = None,
        location: Optional[str] = None,
        content: Optional[str] = None,
        priority: Optional[int] = None,
        remind_offsets: Optional[List[int]] = None,
        request_id: Optional[str] = None,
) -> str:
    """
    新建一条日程或待办。

    什么时候该用：用户说「记一下明天下午三点和产品开会」「提醒我周五交周报」
    「下周一上午体检」——凡是要落在具体时间点上、以后还要回看的事。

    两种类型怎么选：
    - schedule（日程）：有明确的开始时间，占一个时间段，比如会议、体检、出行；
    - todo（待办）：只有截止时间或干脆没有时间，做完打勾即可，比如「买牛奶」。

    什么时候不该用：随手记的一段文字、没有时间也不打算打勾的内容，用备忘录工具。

    时间怎么写：必须是绝对时间，格式「2026-11-21 15:00:00」或「2026-11-21」；
    用户说「明天下午三点」，你要先按当前日期换算成具体日期再传，不要传相对说法。
    日程必须给 start_time；待办建议给 due_time——没有截止时间的待办，
    提醒功能用不了（定了提醒却没有可算的时刻）。

    :param config: 运行时上下文，由框架注入
    :param item_type: schedule-日程 todo-待办
    :param title: 标题，一句话说清是什么事
    :param start_time: 开始时间，日程必填
    :param end_time: 结束时间，可空
    :param due_time: 截止时间，待办使用
    :param location: 地点，仅日程
    :param content: 备注、详细内容
    :param priority: 优先级 0-低 1-中 2-高，默认中
    :param remind_offsets: 提前多少分钟提醒，如 [30] 表示提前半小时、[0] 表示准时；
                           最多 10 个，最远提前 7 天
    :param request_id: 幂等键，可选。同一次重试传同一个值只会建一条；
                       注意删除后用同一个键重建会被拒（服务端唯一索引仍占着），
                       所以只在明确重放上一次请求时传
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    body = _build_item_body(
        item_type=item_type, title=title, content=content, location=location,
        start_time=start_time, end_time=end_time, due_time=due_time,
        priority=priority, remind_offsets=remind_offsets,
    )
    if request_id:
        body["requestId"] = request_id

    try:
        item = await schedule_service.create_item(identity, body)
    except ServiceError as e:
        return f"创建失败：{e.message}"

    logger.info("agent 创建事项成功：user_id=%s item_id=%s", identity.user_id, (item or {}).get("id"))
    return f"已创建，{_fmt_item(item)}"


@tool(name_or_callable="schedule_update_item")
async def schedule_update_item(
        config: RunnableConfig,
        item_id: int,
        title: Optional[str] = None,
        content: Optional[str] = None,
        location: Optional[str] = None,
        start_time: Optional[str] = None,
        end_time: Optional[str] = None,
        due_time: Optional[str] = None,
        priority: Optional[int] = None,
        remind_offsets: Optional[List[int]] = None,
        clear_fields: Optional[List[str]] = None,
) -> str:
    """
    修改一条已有事项，只传要改的字段。

    什么时候该用：用户说「把那个会改到四点」「地点改成三号会议室」「这个不急，降到低优先级」。
    改时间后提醒会自动按原提前量重算，不需要另外调工具。

    什么时候不该用：只是改状态（完成 / 取消）——那两个有专门工具，语义不同，
    混用会让「已完成」和「已取消」在用户眼里失去区别。

    字段留空表示不改，不是清空。要清空某个字段，把字段名放进 clear_fields。
    这两件事必须分开传：只传空值的话，和「这次不改」无法区分。

    :param config: 运行时上下文，由框架注入
    :param item_id: 要改的事项ID
    :param title: 新标题
    :param content: 新备注内容
    :param location: 新地点
    :param start_time: 新开始时间，格式「2026-11-21 15:00:00」
    :param end_time: 新结束时间
    :param due_time: 新截止时间
    :param priority: 新优先级 0-低 1-中 2-高
    :param remind_offsets: 新的提醒偏移；空列表 [] 表示取消全部提醒；
                           不传表示保留原提醒不变
    :param clear_fields: 要清空的字段名，可选值 content / location / endTime / dueTime / tags，
                         例如 ["location"] 表示把地点去掉
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    body = _build_item_body(
        title=title, content=content, location=location,
        start_time=start_time, end_time=end_time, due_time=due_time,
        priority=priority, remind_offsets=remind_offsets,
    )
    body["id"] = item_id
    if clear_fields:
        body["clearFields"] = clear_fields

    if len(body) == 1:
        return "没有要修改的内容，这次调用被忽略了。"

    try:
        item = await schedule_service.update_item(identity, body)
    except ServiceError as e:
        return f"修改失败：{e.message}"

    logger.info("agent 修改事项成功：user_id=%s item_id=%s", identity.user_id, item_id)
    return f"已修改，{_fmt_item(item)}"


@tool(name_or_callable="schedule_complete_item")
async def schedule_complete_item(
        config: RunnableConfig,
        item_id: int,
        completed: bool = True,
) -> str:
    """
    把一条待办 / 日程标记为已完成，或撤销完成。

    什么时候该用：用户说「买牛奶那件事做完了」「刚才那个会开完了」。
    标记完成后该事项尚未发出的提醒会被自动取消——事情都做完了不该再弹提醒。

    撤销完成传 completed=false，用于「我刚才勾错了」。

    :param config: 运行时上下文，由框架注入
    :param item_id: 事项ID
    :param completed: true-标记完成（默认） false-撤销完成
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        await schedule_service.complete_item(identity, item_id, completed)
    except ServiceError as e:
        return f"操作失败：{e.message}"

    # 完成接口返回的是 Result<Void>（data 为 null），回查一次拿真实状态再告诉模型。
    # 不回查就只能说「已标记完成」，模型无法确认改没改成功，也没法向用户复述当前状态
    action = "已标记完成" if completed else "已撤销完成"
    logger.info("agent 变更完成状态：user_id=%s item_id=%s completed=%s", identity.user_id, item_id, completed)
    return f"{action}，{await _detail_after_write(identity, item_id)}"


@tool(name_or_callable="schedule_cancel_item")
async def schedule_cancel_item(config: RunnableConfig, item_id: int) -> str:
    """
    取消一条事项（不做了，但保留记录）。

    什么时候该用：用户说「那个会取消了」「这趟行程不去了」——事情不会发生，
    但以后要能查到它曾经存在过。

    与删除的区别很重要：取消后仍能在「已取消」里翻到，也能一键恢复；
    删除是逻辑删除，列表和统计里彻底看不见。
    用户只是不做了，用这个；用户说「删掉，别让我再看到」，用删除工具。

    :param config: 运行时上下文，由框架注入
    :param item_id: 事项ID
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        await schedule_service.cancel_item(identity, item_id)
    except ServiceError as e:
        return f"取消失败：{e.message}"

    logger.info("agent 取消事项：user_id=%s item_id=%s", identity.user_id, item_id)
    return f"已取消，{await _detail_after_write(identity, item_id)}"


@tool(name_or_callable="schedule_delete_item")
async def schedule_delete_item(config: RunnableConfig, item_id: int) -> str:
    """
    删除一条事项（不可在列表里恢复）。

    什么时候该用：用户明确说「删掉」「别让我再看到这条」，
    或者发现是重复建的、建错了需要清掉。

    只是不做了（比如会议取消）不要用这个，用取消——删掉会让这段历史凭空消失。

    :param config: 运行时上下文，由框架注入
    :param item_id: 事项ID
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        await schedule_service.delete_item(identity, item_id)
    except ServiceError as e:
        return f"删除失败：{e.message}"

    logger.info("agent 删除事项：user_id=%s item_id=%s", identity.user_id, item_id)
    return f"已删除事项 id={item_id}。"


@tool(name_or_callable="schedule_batch_complete")
async def schedule_batch_complete(
        config: RunnableConfig,
        item_ids: List[int],
        completed: bool = True,
) -> str:
    """
    一次把多条事项标记完成（或撤销完成）。

    什么时候该用：用户说「今天的待办全做完了」「这些都不用做了」，
    或者你要对一批 id 做同样的状态变更。

    能一次调用就不要循环单条：循环是 N 次请求，中途失败会留下
    「一半完成一半没有」且没法解释的状态。这个工具逐条回报，
    哪几条成功、哪几条为什么失败都会告诉你，可以如实转述给用户。

    :param config: 运行时上下文，由框架注入
    :param item_ids: 事项ID列表，最多 100 条
    :param completed: true-全部标记完成（默认） false-全部撤销完成
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY
    if not item_ids:
        return "没有给出事项ID，这次调用被忽略了。"

    try:
        result = await schedule_service.batch_complete(identity, item_ids, completed)
    except ServiceError as e:
        return f"批量操作失败：{e.message}"

    logger.info("agent 批量变更完成状态：user_id=%s count=%s completed=%s",
                identity.user_id, len(item_ids), completed)
    return _fmt_batch(result, "标记完成" if completed else "撤销完成")


@tool(name_or_callable="schedule_batch_delete")
async def schedule_batch_delete(config: RunnableConfig, item_ids: List[int]) -> str:
    """
    一次删除多条事项。

    什么时候该用：用户说「把上周那些过期的待办都删了」，或者清理重复项。
    拿不到确切 id 时，先用查询工具筛出来，再拿 id 调这个。

    :param config: 运行时上下文，由框架注入
    :param item_ids: 事项ID列表，最多 100 条
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY
    if not item_ids:
        return "没有给出事项ID，这次调用被忽略了。"

    try:
        result = await schedule_service.batch_delete(identity, item_ids)
    except ServiceError as e:
        return f"批量删除失败：{e.message}"

    logger.info("agent 批量删除事项：user_id=%s count=%s", identity.user_id, len(item_ids))
    return _fmt_batch(result, "批量删除")


# ======================== 重复系列：写操作（敏感） ========================

@tool(name_or_callable="schedule_create_series")
async def schedule_create_series(
        config: RunnableConfig,
        item_type: Literal["schedule", "todo"],
        title: str,
        freq: Literal["DAILY", "WEEKLY", "MONTHLY"],
        base_time: str,
        count_limit: Optional[int] = None,
        until_date: Optional[str] = None,
        interval: int = 1,
        by_day: Optional[List[str]] = None,
        duration_minutes: Optional[int] = None,
        location: Optional[str] = None,
        content: Optional[str] = None,
        priority: Optional[int] = None,
        remind_offsets: Optional[List[int]] = None,
        request_id: Optional[str] = None,
) -> str:
    """
    新建一条重复日程 / 待办（如「每周三晚上健身」「每天八点吃药」）。

    什么时候该用：用户说的是一个**会重复发生的规律**，而不是某一个具体日子——
    「每周三」「每隔两天」「每月一号」「周一到周五」。这时不要用 schedule_create_item
    一条条建，那条工具只建一次。

    什么时候不该用：只是这一次的时间点，用 schedule_create_item。

    **重复必须有终点**：count_limit 与 until_date 至少给一个。
    用户没说重复多久时必须先问一句（「先来 12 次，还是到年底？」），
    不要自己拍一个数——拍错了用户要一条条删。

    base_time 对两种类型含义不同，这是最容易用错的地方：
    - 日程：填每次的**开始时间**，如「2026-11-25 19:00:00」；
    - 待办：填每次的**截止时间**。
    展开出来的每一条都按它往后推，时分保持一致。

    by_day 只在 freq=WEEKLY 时有意义，取值 MO/TU/WE/TH/FR/SA/SU。
    用户说「周一到周五」就传 ["MO","TU","WE","TH","FR"]；
    说「每周三」可以只传 ["WE"]，也可以不传（默认取 base_time 那天是周几）。

    时间必须是绝对时间：「下周三」要先按当前日期换算成具体日期再传。

    :param config: 运行时上下文，由框架注入
    :param item_type: schedule-日程 todo-待办
    :param title: 标题
    :param freq: DAILY-每天 WEEKLY-每周 MONTHLY-每月
    :param base_time: 锚点时刻，日程填开始时间、待办填截止时间，如「2026-11-25 19:00:00」
    :param count_limit: 总次数上限（含第一次），1~100；与 until_date 至少给一个
    :param until_date: 终止日期（含当天），如「2026-12-31」；与 count_limit 至少给一个
    :param interval: 间隔，每 N 天/周/月，默认 1
    :param by_day: 星期几，仅 WEEKLY 使用，如 ["MO","WE"]
    :param duration_minutes: 每次时长（分钟），仅日程；不填则结束时间为空
    :param location: 地点，仅日程
    :param content: 备注
    :param priority: 优先级 0-低 1-中 2-高
    :param remind_offsets: 每次提前多少分钟提醒，如 [30]；最多 10 个
    :param request_id: 幂等键，可选。只在明确重放上一次请求时传
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY
    if not count_limit and not until_date:
        # 在本地就拦住，不留给下游报错：这句提示会直接进模型上下文，
        # 让它知道下一步该问用户什么，而不是把 400 转述成「服务出错了」
        return "重复必须有终点：请向用户确认是「重复几次」还是「重复到哪天」，然后传 count_limit 或 until_date。"

    body: Dict[str, Any] = {
        "itemType": item_type,
        "title": title,
        "freq": freq,
        "baseTime": base_time,
        "interval": interval,
    }
    optional = {
        "countLimit": count_limit,
        "untilDate": until_date,
        "byDay": by_day,
        "durationMinutes": duration_minutes,
        "location": location,
        "content": content,
        "priority": priority,
        "remindOffsets": remind_offsets,
        "requestId": request_id,
    }
    body.update({k: v for k, v in optional.items() if v is not None})

    try:
        series = await schedule_service.create_series(identity, body)
    except ServiceError as e:
        return f"创建重复计划失败：{e.message}"

    logger.info("agent 创建重复系列：user_id=%s series_id=%s count=%s",
                identity.user_id, (series or {}).get("id"), (series or {}).get("itemCount"))
    return f"已创建，{_fmt_series(series)}"


@tool(name_or_callable="schedule_update_series")
async def schedule_update_series(
        config: RunnableConfig,
        series_id: int,
        scope: Literal["all", "future"],
        anchor_item_id: Optional[int] = None,
        title: Optional[str] = None,
        content: Optional[str] = None,
        location: Optional[str] = None,
        priority: Optional[int] = None,
        tags: Optional[str] = None,
        freq: Optional[Literal["DAILY", "WEEKLY", "MONTHLY"]] = None,
        interval: Optional[int] = None,
        by_day: Optional[List[str]] = None,
        until_date: Optional[str] = None,
        count_limit: Optional[int] = None,
        remind_offsets: Optional[List[int]] = None,
) -> str:
    """
    修改一条重复计划，改整个系列或从某一次起往后改。

    scope 怎么选：
    - all：整个系列都改（「健身这个名字改成训练」）；
    - future：只从 anchor_item_id 那一次起改，之前的保持原样
      （「下周三之后改成周一」——改排期用 freq / by_day）。

    这个工具改不了「几点开始」：锚点时刻是系列的定义，改它等于换一个系列。
    只是某一天改钟点（「这周三挪到八点」），用 schedule_update_item 改那一条实例。
    要整体换时间，只能删掉这个系列按新时间重建。

    只改一次（「这周这次挪到八点」）不要用这个工具，用 schedule_update_item 直接改那条实例。

    规则字段（freq / interval / by_day / until_date / count_limit）只要传了一个，
    下游会重排未来所有实例；只传内容字段则只改标题、备注这些，不动日子。

    改内容只影响**未来且未完成**的实例，已经发生或已完成的保持原样。

    :param config: 运行时上下文，由框架注入
    :param series_id: 系列ID
    :param scope: all-整个系列 future-从 anchor_item_id 起往后
    :param anchor_item_id: 锚点实例ID，scope=future 时必填
    :param title: 新标题
    :param content: 新备注
    :param location: 新地点
    :param priority: 新优先级 0-低 1-中 2-高
    :param tags: 新标签
    :param freq: 新频率 DAILY / WEEKLY / MONTHLY
    :param interval: 新间隔，每 N 天/周/月
    :param by_day: 新星期几，仅 WEEKLY
    :param until_date: 新终止日期，如「2026-12-31」
    :param count_limit: 新总次数上限
    :param remind_offsets: 新提醒偏移；空列表 [] 表示取消全部提醒；不传表示不改
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY
    if scope == "future" and anchor_item_id is None:
        return "scope=future 时必须给出 anchor_item_id（从哪一次起改）。可以先调 schedule_list_items 按 seriesId 查出各次的 id。"

    body: Dict[str, Any] = {"id": series_id, "scope": scope}
    optional = {
        "anchorItemId": anchor_item_id,
        "title": title,
        "content": content,
        "location": location,
        "priority": priority,
        "tags": tags,
        "freq": freq,
        "interval": interval,
        "byDay": by_day,
        "untilDate": until_date,
        "countLimit": count_limit,
        "remindOffsets": remind_offsets,
    }
    body.update({k: v for k, v in optional.items() if v is not None})

    if len(body) == 2:
        return "没有要修改的内容，这次调用被忽略了。"

    try:
        series = await schedule_service.update_series(identity, body)
    except ServiceError as e:
        return f"修改重复计划失败：{e.message}"

    logger.info("agent 修改重复系列：user_id=%s series_id=%s scope=%s",
                identity.user_id, series_id, scope)
    return f"已修改，{_fmt_series(series)}"


@tool(name_or_callable="schedule_delete_series")
async def schedule_delete_series(
        config: RunnableConfig,
        series_id: int,
        scope: Literal["all", "future"],
        anchor_item_id: Optional[int] = None,
) -> str:
    """
    停掉一条重复计划。

    scope 怎么选：
    - all：整个系列都删掉（「以后不健身了」）；
    - future：只从 anchor_item_id 那一次起不再发生，之前的记录还在
      （「这学期结束就不上了」）。

    已经发生过的那几次，两种范围下都保留——删规则不等于抹掉历史。

    只是这一次不去（「这周三请假」）不要用这个，用 schedule_cancel_item 取消那一条实例。

    :param config: 运行时上下文，由框架注入
    :param series_id: 系列ID
    :param scope: all-整个系列 future-从 anchor_item_id 起往后
    :param anchor_item_id: 锚点实例ID，scope=future 时必填
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY
    if scope == "future" and anchor_item_id is None:
        return "scope=future 时必须给出 anchor_item_id（从哪一次起不再发生）。"

    body: Dict[str, Any] = {"id": series_id, "scope": scope}
    if anchor_item_id is not None:
        body["anchorItemId"] = anchor_item_id

    try:
        await schedule_service.delete_series(identity, body)
    except ServiceError as e:
        return f"停止重复计划失败：{e.message}"

    logger.info("agent 删除重复系列：user_id=%s series_id=%s scope=%s",
                identity.user_id, series_id, scope)
    if scope == "all":
        return f"已删除重复计划 id={series_id}，未来的安排都不会再生成。"
    return f"已从 id={anchor_item_id} 起停止，之前的记录保留。"


@tool(name_or_callable="schedule_list_series")
async def schedule_list_series(
        config: RunnableConfig,
        item_type: Optional[Literal["schedule", "todo"]] = None,
) -> str:
    """
    列出我的全部重复计划（每周几次的那种），不含单次事项。

    什么时候该用：用户问「我有哪些固定安排」「我有几个重复的日程」，
    或者要先拿到 series_id 才能改 / 停掉某一条。

    返回里带每条已经展开多少条、首次和末次的实例 id：
    「还剩几次」用 itemCount 结合今天的日期判断，别直接说 countLimit。

    :param config: 运行时上下文，由框架注入
    :param item_type: schedule / todo，不传查全部
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    params: Dict[str, Any] = {}
    if item_type:
        params["itemType"] = item_type

    try:
        series_list = await schedule_service.list_series(identity, params)
    except ServiceError as e:
        return f"查询重复计划失败：{e.message}"

    if not series_list:
        return "目前没有任何重复计划。"
    lines = [f"共 {len(series_list)} 条重复计划："]
    lines.extend(f"- {_fmt_series(series)}" for series in series_list)
    return "\n".join(lines)


# ======================== 事项：读操作（免确认） ========================

@tool(name_or_callable="schedule_get_item")
async def schedule_get_item(config: RunnableConfig, item_id: int) -> str:
    """
    查一条事项的完整信息（含还没发出的提醒）。

    什么时候该用：你已经知道 id，要确认细节——「那个会具体几点」「我之前设的提醒是提前多久」；
    或者刚做完写操作，想核对结果。

    不知道 id 时不要用这个，先用列表工具按条件找。

    :param config: 运行时上下文，由框架注入
    :param item_id: 事项ID
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        item = await schedule_service.get_item(identity, item_id)
    except ServiceError as e:
        return f"查询失败：{e.message}"

    return _fmt_item(item)


@tool(name_or_callable="schedule_list_items")
async def schedule_list_items(
        config: RunnableConfig,
        item_type: Optional[Literal["schedule", "todo"]] = None,
        status: Optional[int] = None,
        keyword: Optional[str] = None,
        date: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        overdue: Optional[bool] = None,
        page_num: Optional[int] = None,
        page_size: Optional[int] = None,
) -> str:
    """
    分页查询事项。用户问「我今天有什么事」「这周有什么安排」「还有哪些没做完」时用这个。

    几种典型问法怎么传参：
    - 「今天有什么」→ date="2026-11-21"
    - 「这周安排」→ start_date="2026-11-17", end_date="2026-11-23"
    - 「还没做完的」→ status=0
    - 「逾期了哪些」→ overdue=true
    - 「关于开会的」→ keyword="会"

    date 与 start_date/end_date 是两种口径，不要同时用。
    overdue=true 时 item_type 和 status 会被忽略——「逾期」本身已经限定了
    「待办 + 未完成」。

    :param config: 运行时上下文，由框架注入
    :param item_type: schedule-只要日程 todo-只要待办，不传查全部
    :param status: 0-未完成 1-已完成 2-已取消，不传查全部
    :param keyword: 标题/内容关键词
    :param date: 只看某一天，格式 2026-11-21
    :param start_date: 区间起点（含），格式 2026-11-17
    :param end_date: 区间终点（含），格式 2026-11-23
    :param overdue: true 时只返回逾期未完成的待办
    :param page_num: 页码，从 1 开始
    :param page_size: 每页条数，默认 20，上限 50
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    params = {
        "itemType": item_type,
        "status": status,
        "keyword": keyword,
        "date": date,
        "startDate": start_date,
        "endDate": end_date,
        "overdue": overdue,
        "pageNum": page_num or 1,
        "pageSize": min(page_size or 20, MAX_PAGE_SIZE),
    }

    try:
        page = await schedule_service.list_items(identity, params)
    except ServiceError as e:
        return f"查询失败：{e.message}"

    return _fmt_page(page)


@tool(name_or_callable="schedule_overview")
async def schedule_overview(config: RunnableConfig) -> str:
    """
    看板数字：今天几个日程、今天几个待办、完成几个、逾期几个、本周完成率。

    什么时候该用：用户问「我今天忙不忙」「最近效率怎么样」这类**概况**问题。
    要具体条目（「是哪几件事」）时用列表工具，这里只有数字。

    :param config: 运行时上下文，由框架注入
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        data = await schedule_service.overview(identity) or {}
    except ServiceError as e:
        return f"查询失败：{e.message}"

    return (
        f"今日日程 {data.get('todayScheduleCount', 0)} 个；"
        f"今日待办 {data.get('todayTodoCount', 0)} 个（已完成 {data.get('todayTodoDoneCount', 0)}）；"
        f"逾期未完成 {data.get('overdueTodoCount', 0)} 个；"
        f"本周待办 {data.get('weekTodoCount', 0)} 个（完成 {data.get('weekTodoDoneCount', 0)}，"
        f"完成率 {data.get('weekCompletionRate', 0)}%）。"
    )


@tool(name_or_callable="schedule_daily_stats")
async def schedule_daily_stats(
        config: RunnableConfig,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
) -> str:
    """
    按天统计的事项数量，用来画柱状图或回答「这周哪天最忙」。

    只返回有数据的日子，没有事项的日期不会补 0——需要连续日期轴时你自己按区间补。

    什么时候该用：用户要求看趋势、图表数据，或者问「最近哪天事最多」。
    「今天有几件事」这种单点问题用概览工具更直接。

    :param config: 运行时上下文，由框架注入
    :param start_date: 起始日期，格式 2026-11-17；不传由服务端决定
    :param end_date: 结束日期，格式 2026-11-23
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        rows = await schedule_service.daily_stats(identity, start_date, end_date) or []
    except ServiceError as e:
        return f"查询失败：{e.message}"

    if not rows:
        return "这段时间没有任何事项。"

    lines = [f"{len(rows)} 天有事项："]
    lines.extend(
        f"- {row.get('date')}：日程 {row.get('scheduleCount', 0)} 个，"
        f"待办 {row.get('todoCount', 0)} 个（完成 {row.get('todoDoneCount', 0)}）"
        for row in rows
    )
    return "\n".join(lines)


@tool(name_or_callable="schedule_upcoming_reminders")
async def schedule_upcoming_reminders(config: RunnableConfig, days: int = 3) -> str:
    """
    未来几天内即将触发的提醒。

    什么时候该用：用户问「接下来几天会提醒我什么」「我设了哪些提醒」，
    或者你要确认某个提醒到底有没有设上。

    :param config: 运行时上下文，由框架注入
    :param days: 往后看几天，默认 3
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        rows = await schedule_service.upcoming_reminders(identity, days) or []
    except ServiceError as e:
        return f"查询失败：{e.message}"

    if not rows:
        return f"未来 {days} 天内没有将要触发的提醒。"

    lines = [f"未来 {days} 天有 {len(rows)} 条提醒："]
    for row in rows:
        offset = row.get("offsetMinutes")
        offset_text = "准时" if offset == 0 else f"提前{offset}分钟"
        lines.append(f"- {row.get('remindTime')} 提醒「{row.get('title')}」（{offset_text}）")
    return "\n".join(lines)


# ======================== 备忘录 ========================

@tool(name_or_callable="schedule_memo_create")
async def schedule_memo_create(
        config: RunnableConfig,
        content: str,
        title: Optional[str] = None,
        tags: Optional[str] = None,
        pinned: Optional[bool] = None,
) -> str:
    """
    记一条备忘录：没有明确时间点、也不需要打勾的随手记。

    什么时候该用：用户说「记一下这个想法」「备忘：身份证放在抽屉第二层」
    「记一下他家孩子叫乐乐」——内容要留下，但没有截止时间、不做完也不会有负罪感。

    什么时候不该用：带时间的安排用日程/待办工具，混着用会让用户在该看到的地方看不到。

    :param config: 运行时上下文，由框架注入
    :param content: 正文，必填
    :param title: 标题，可空
    :param tags: 标签，逗号分隔，如「工作,灵感」
    :param pinned: true 表示置顶
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    body: Dict[str, Any] = {"content": content}
    if title:
        body["title"] = title
    if tags:
        body["tags"] = tags
    if pinned is not None:
        body["pinned"] = 1 if pinned else 0

    try:
        memo = await schedule_service.create_memo(identity, body)
    except ServiceError as e:
        return f"记录失败：{e.message}"

    logger.info("agent 创建备忘录：user_id=%s memo_id=%s", identity.user_id, (memo or {}).get("id"))
    return f"已记录，{_fmt_memo(memo)}"


@tool(name_or_callable="schedule_memo_update")
async def schedule_memo_update(
        config: RunnableConfig,
        memo_id: int,
        title: Optional[str] = None,
        content: Optional[str] = None,
        tags: Optional[str] = None,
        pinned: Optional[bool] = None,
) -> str:
    """
    修改一条备忘录，只传要改的字段（置顶也走这里）。

    什么时候该用：用户说「把那条备忘改成…」「把身份证那条置顶」。

    正文不能改成空：一条没有内容的备忘录没有意义，想清掉就直接删除。

    :param config: 运行时上下文，由框架注入
    :param memo_id: 备忘录ID
    :param title: 新标题
    :param content: 新正文
    :param tags: 新标签，逗号分隔
    :param pinned: true-置顶 false-取消置顶
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    body: Dict[str, Any] = {"id": memo_id}
    if title is not None:
        body["title"] = title
    if content is not None:
        body["content"] = content
    if tags is not None:
        body["tags"] = tags
    if pinned is not None:
        body["pinned"] = 1 if pinned else 0

    if len(body) == 1:
        return "没有要修改的内容，这次调用被忽略了。"

    try:
        memo = await schedule_service.update_memo(identity, body)
    except ServiceError as e:
        return f"修改失败：{e.message}"

    logger.info("agent 修改备忘录：user_id=%s memo_id=%s", identity.user_id, memo_id)
    return f"已修改，{_fmt_memo(memo)}"


@tool(name_or_callable="schedule_memo_delete")
async def schedule_memo_delete(config: RunnableConfig, memo_id: int) -> str:
    """
    删除一条备忘录。

    什么时候该用：用户说「那条备忘不用了，删掉」。

    :param config: 运行时上下文，由框架注入
    :param memo_id: 备忘录ID
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        await schedule_service.delete_memo(identity, memo_id)
    except ServiceError as e:
        return f"删除失败：{e.message}"

    logger.info("agent 删除备忘录：user_id=%s memo_id=%s", identity.user_id, memo_id)
    return f"已删除备忘录 id={memo_id}。"


@tool(name_or_callable="schedule_memo_get")
async def schedule_memo_get(config: RunnableConfig, memo_id: int) -> str:
    """
    查一条备忘录的完整内容。

    什么时候该用：已知 id，要看全文（列表里只有摘要）。

    :param config: 运行时上下文，由框架注入
    :param memo_id: 备忘录ID
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    try:
        memo = await schedule_service.get_memo(identity, memo_id)
    except ServiceError as e:
        return f"查询失败：{e.message}"

    return _fmt_memo(memo)


@tool(name_or_callable="schedule_memo_list")
async def schedule_memo_list(
        config: RunnableConfig,
        keyword: Optional[str] = None,
        tags: Optional[str] = None,
        page_num: Optional[int] = None,
        page_size: Optional[int] = None,
) -> str:
    """
    分页查询备忘录。

    什么时候该用：用户问「我之前记过什么」「我关于车贷的备忘在哪」。

    :param config: 运行时上下文，由框架注入
    :param keyword: 标题/正文关键词
    :param tags: 标签，包含匹配
    :param page_num: 页码，从 1 开始
    :param page_size: 每页条数，默认 20，上限 50
    """
    identity = _identity(config)
    if identity is None:
        return NO_IDENTITY

    params = {
        "keyword": keyword,
        "tags": tags,
        "pageNum": page_num or 1,
        "pageSize": min(page_size or 20, MAX_PAGE_SIZE),
    }

    try:
        page = await schedule_service.list_memos(identity, params)
    except ServiceError as e:
        return f"查询失败：{e.message}"

    records = (page or {}).get("records") or []
    if not records:
        return "没有找到符合条件的备忘录。"

    lines = [f"共 {page.get('total')} 条，本页 {len(records)} 条："]
    lines.extend(f"- {_fmt_memo(memo)}" for memo in records)
    return "\n".join(lines)


# 日程插件提供的全部工具。顺序即 bind_tools 时模型看到的顺序：
# 把「记事情」和「查事情」放前面，「改/删」放后面——前两类是高频入口。
SCHEDULE_TOOLS = (
    schedule_create_item,
    schedule_list_items,
    schedule_get_item,
    schedule_overview,
    schedule_daily_stats,
    schedule_update_item,
    schedule_complete_item,
    schedule_cancel_item,
    schedule_delete_item,
    schedule_batch_complete,
    schedule_batch_delete,
    schedule_upcoming_reminders,
    schedule_create_series,
    schedule_update_series,
    schedule_delete_series,
    schedule_list_series,
    schedule_memo_create,
    schedule_memo_list,
    schedule_memo_get,
    schedule_memo_update,
    schedule_memo_delete,
)
