/**
 * 前端插件注册表。这一层是「插件服务的 UI」能成立的地方，不是装饰。
 *
 * 后端的插件粒度有三处已经定死：`plugin` 表的 `plugin_key`、agent 的
 * `SERVICE_TOOLS = {"<key>": (...)}`、以及 SSE 里 `ui` 帧的 `plugin` 字段。
 * 前端注册表是第四处，键必须与它们逐字相同——写错一个字母的后果不是报错，
 * 而是「agent 推了面板事件，界面没反应」，最难查的那类问题。
 *
 * 与后端的差别（有意为之）：插件**列表**仍然来自 account 的 `/plugin/list`，
 * 因为那是用户可开关的事实；而这里声明的是「哪些插件前端已经做出来了」。
 * 两者取交集才是可点开的插件：账单、健康在服务端还没有实现，
 * 在界面里就显示成「即将开放」，而不是给一个点了会 404 的入口。
 */
import type { Component } from 'vue'
import SchedulePanel from '../plugins/schedule/SchedulePanel.vue'

export interface PluginManifest {
  /** 必须与后端 plugin_key 完全一致 */
  key: string
  /** 侧栏显示名。与后端 name 同口径但更短，侧栏宽度只够两三个字 */
  name: string
  /** 未启用时说明这个插件能做什么，一句话 */
  hint: string
  /** 面板根组件 */
  panel: Component
  /** 面板内默认打开的分段 */
  defaultTab: string
  /**
   * SSE `ui` 帧的 `view` → 面板内分段。
   * 映射不到就落在 defaultTab，宁可显示默认面板，也不要空白。
   */
  views: Record<string, string>
  /** 图标路径。用内联 SVG 而不是图标字体：一个插件一条路径，不必为此引一个库 */
  icon: string
}

export const PLUGIN_MANIFESTS: PluginManifest[] = [
  {
    key: 'schedule',
    name: '日程',
    hint: '日程、待办、提醒与备忘',
    panel: SchedulePanel,
    defaultTab: 'day',
    views: {
      // agent 侧 _TOOL_UI 里登记过的全部 view 值
      week: 'day',
      list: 'list',
      memo: 'memo',
      reminder: 'day',
      stats: 'day',
    },
    icon: 'M3 4.5h14M3 11h14M3 17.5h9',
  },
]

const byKey = new Map(PLUGIN_MANIFESTS.map((item) => [item.key, item]))

/** 找前端已经实现的插件；没有 manifest 的返回 undefined，调用方据此显示「即将开放」 */
export function resolvePlugin(key: string): PluginManifest | undefined {
  return byKey.get(key)
}

/** ui 帧的 view 归一成面板分段 */
export function resolveTab(manifest: PluginManifest, view?: string): string {
  if (!view) return manifest.defaultTab
  return manifest.views[view] ?? manifest.defaultTab
}
