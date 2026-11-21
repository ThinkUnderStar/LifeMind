/**
 * 插件市场。返回的 enabled 是「没配置过就按默认值折算」之后的最终状态，
 * 前端直接按它渲染开关，不需要自己再套一层默认值逻辑。
 */
import { SERVICE, get, post } from './http'
import type { PluginInfo } from '../core/types'

export function listPlugins(): Promise<PluginInfo[]> {
  return get<PluginInfo[]>(`${SERVICE.account}/plugin/list`)
}

export function setPluginEnabled(pluginKey: string, enabled: boolean): Promise<void> {
  return post<void>(`${SERVICE.account}/plugin/enable`, { pluginKey, enabled })
}
