"""
日程服务（life-mind-schedule，8005）的调用封装。

一个方法对应一个 HTTP 接口，参数名保持 Python 风格，请求体里再转成下游的
驼峰字段——这样工具层的签名看起来是「自然语言里那几个概念」，
而不是被 Java 的字段命名绑住。

本模块不做业务判断（不校验日程有没有开始时间、不判断提醒该不该建），
这些规则在服务端有一份权威实现；agent 这边再写一遍只会出现两边不一致。
"""
from typing import Any, Dict, List, Optional

from app.client.base import Identity, request_json
from app.core.settings import settings

SERVICE_NAME = "日程服务"


class ScheduleService:
    """
    日程服务的门面。所有方法都要求传 Identity，下游据此做数据归属与权限构建。
    """

    @property
    def base_url(self) -> str:
        # 每次读 settings 而不是构造时缓存：Nacos 热更新后能立刻用上新地址
        return settings.SCHEDULE_SERVICE_URL

    async def _call(self, identity: Identity, method: str, path: str, **kwargs: Any) -> Any:
        return await request_json(
            service_name=SERVICE_NAME,
            base_url=self.base_url,
            method=method,
            path=path,
            identity=identity,
            **kwargs,
        )

    # ==================== 事项 ====================

    async def create_item(self, identity: Identity, body: Dict[str, Any]) -> Dict[str, Any]:
        """创建日程 / 待办，返回新建的 ItemResponse。"""
        return await self._call(identity, "POST", "/item/create", body=body)

    async def update_item(self, identity: Identity, body: Dict[str, Any]) -> Dict[str, Any]:
        """部分更新事项，返回更新后的 ItemResponse。"""
        return await self._call(identity, "PUT", "/item/update", body=body)

    async def complete_item(self, identity: Identity, item_id: int, completed: bool) -> Dict[str, Any]:
        """标记完成 / 取消完成。"""
        return await self._call(
            identity, "PUT", "/item/complete",
            body={"id": item_id, "completed": completed},
        )

    async def cancel_item(self, identity: Identity, item_id: int) -> Dict[str, Any]:
        """取消事项（status=2，可恢复、可查历史），不是删除。"""
        return await self._call(identity, "PUT", "/item/cancel", body={"id": item_id})

    async def delete_item(self, identity: Identity, item_id: int) -> None:
        """逻辑删除事项，列表与统计里都不再出现。"""
        return await self._call(identity, "DELETE", f"/item/{int(item_id)}")

    async def batch_complete(self, identity: Identity, ids: List[int], completed: bool) -> Dict[str, Any]:
        """批量变更完成状态，返回逐条成功/失败明细。"""
        return await self._call(
            identity, "PUT", "/item/batch/complete",
            body={"ids": ids, "completed": completed},
        )

    async def batch_delete(self, identity: Identity, ids: List[int]) -> Dict[str, Any]:
        """批量删除，返回逐条成功/失败明细。"""
        return await self._call(identity, "POST", "/item/batch/delete", body={"ids": ids})

    async def get_item(self, identity: Identity, item_id: int) -> Dict[str, Any]:
        """取单条事项详情（含尚未发送的提醒偏移）。"""
        return await self._call(identity, "GET", f"/item/{int(item_id)}")

    async def get_items(self, identity: Identity, ids: List[int]) -> List[Dict[str, Any]]:
        """按 id 批量取详情，返回顺序与入参一致，不存在的 id 会被跳过。"""
        return await self._call(identity, "GET", "/item/batch", params={"ids": ",".join(str(i) for i in ids)})

    async def list_items(self, identity: Identity, params: Dict[str, Any]) -> Dict[str, Any]:
        """分页查询事项，返回 {records, total, pageNum, pageSize ...}。"""
        return await self._call(identity, "GET", "/item/page", params=params)

    # ==================== 重复系列 ====================

    async def create_series(self, identity: Identity, body: Dict[str, Any]) -> Dict[str, Any]:
        """创建重复规则，下游会按规则展开成多条事项，返回 SeriesResponse。"""
        return await self._call(identity, "POST", "/series/create", body=body)

    async def update_series(self, identity: Identity, body: Dict[str, Any]) -> Dict[str, Any]:
        """按范围（all / future）修改系列，返回 SeriesResponse。"""
        return await self._call(identity, "PUT", "/series/update", body=body)

    async def delete_series(self, identity: Identity, body: Dict[str, Any]) -> None:
        """按范围删除系列。返回 Result<Void>，需要状态就再查一次 list。"""
        return await self._call(identity, "POST", "/series/delete", body=body)

    async def list_series(self, identity: Identity, params: Dict[str, Any]) -> List[Dict[str, Any]]:
        """我的所有重复计划（不分页，下游上限 100 条）。"""
        return await self._call(identity, "GET", "/series/list", params=params)

    async def get_series(self, identity: Identity, series_id: int) -> Dict[str, Any]:
        """系列详情，带展开出的实例条数与首尾实例ID。"""
        return await self._call(identity, "GET", f"/series/{int(series_id)}")

    # ==================== 提醒 ====================

    async def upcoming_reminders(self, identity: Identity, days: int = 3) -> List[Dict[str, Any]]:
        """未来若干天内将要触发的提醒。"""
        return await self._call(identity, "GET", "/reminder/upcoming", params={"days": days})

    # ==================== 统计 ====================

    async def overview(self, identity: Identity) -> Dict[str, Any]:
        """今日与本周的概览数字，供画卡片。"""
        return await self._call(identity, "GET", "/statistics/overview")

    async def daily_stats(
            self,
            identity: Identity,
            start_date: Optional[str] = None,
            end_date: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """按日聚合，供画柱状图。只返回有数据的日子。"""
        return await self._call(
            identity, "GET", "/statistics/daily",
            params={"startDate": start_date, "endDate": end_date},
        )

    # ==================== 备忘录 ====================

    async def create_memo(self, identity: Identity, body: Dict[str, Any]) -> Dict[str, Any]:
        """新建备忘录。"""
        return await self._call(identity, "POST", "/memo/create", body=body)

    async def update_memo(self, identity: Identity, body: Dict[str, Any]) -> Dict[str, Any]:
        """部分更新备忘录（含置顶）。"""
        return await self._call(identity, "PUT", "/memo/update", body=body)

    async def delete_memo(self, identity: Identity, memo_id: int) -> None:
        """删除备忘录。"""
        return await self._call(identity, "DELETE", f"/memo/{int(memo_id)}")

    async def get_memo(self, identity: Identity, memo_id: int) -> Dict[str, Any]:
        """取单条备忘录。"""
        return await self._call(identity, "GET", f"/memo/{int(memo_id)}")

    async def list_memos(self, identity: Identity, params: Dict[str, Any]) -> Dict[str, Any]:
        """分页查询备忘录。"""
        return await self._call(identity, "GET", "/memo/page", params=params)


# 进程内单例：本身无状态（地址每次从 settings 读），工具层直接引用即可
schedule_service = ScheduleService()
