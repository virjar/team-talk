import dayjs from 'dayjs'

export interface AdminPage<T> { total: number; items: T[] }

export type TelemetryPlatform = 'ANDROID' | 'DESKTOP' | 'HEADLESS' | 'UNKNOWN'
export type TelemetryEventCategory =
  | 'FAULT'
  | 'LOG'
  | 'PAGE_DWELL'
  | 'ACTION'
  | 'SYSTEM'
  | 'USER_NOTICE'
  | 'MEDIA'
  | 'OUTGOING_QUEUE'
export type TelemetryPolicyMode = 'BASELINE' | 'DIAGNOSTIC'

export interface TelemetryHighlightSpan {
  start: number
  end: number
}

export interface TelemetryTextHighlight {
  text: string
  spans: TelemetryHighlightSpan[]
}

export interface ConnectionTraceContext {
  correlationId: string
  traceId: string
  sessionId: string
  connectionGeneration: number
  policyRevision: number
}

export interface ConnectionTraceItem extends ConnectionTraceContext {
  id: number
  uid: string | null
  deviceId: string | null
  occurredAt: number
  phase: string
  outcome: string
  detail: string | null
}

export interface ConnectionTraceLookup {
  eventRecordId: number
  context: ConnectionTraceContext | null
  traces: ConnectionTraceItem[]
  truncated: boolean
}

export function hasValidTelemetryHighlightSpans(highlight: TelemetryTextHighlight): boolean {
  let previousEnd = 0
  return highlight.spans.every(span => {
    const valid = Number.isSafeInteger(span.start)
      && Number.isSafeInteger(span.end)
      && span.start >= previousEnd
      && span.end > span.start
      && span.end <= highlight.text.length
    if (valid) previousEnd = span.end
    return valid
  })
}

export interface TelemetryEventItem {
  id: number
  eventId: string
  batchId: string
  uid: string
  deviceId: string
  receivedAt: number
  occurredAt: number
  platform: TelemetryPlatform
  osName: string
  osVersion: string
  architecture: string
  deviceModel: string
  appVersion: string
  buildNumber: string
  gitCommit: string
  buildIdentity: string
  buildTime: string
  protocolVersion: number
  distribution: string
  category: TelemetryEventCategory
  eventName: string
  runId: string
  sequence: number
  message: string | null
  highlight: TelemetryTextHighlight | null
  connectionTraceContext: ConnectionTraceContext | null
}

export interface TelemetryDeviceItem {
  uid: string
  deviceId: string
  platform: TelemetryPlatform
  osName: string
  osVersion: string
  architecture: string
  deviceModel: string
  appVersion: string
  buildNumber: string
  gitCommit: string
  buildIdentity: string
  buildTime: string
  protocolVersion: number
  distribution: string
  firstSeenAt: number
  lastSeenAt: number
  lastEventAt: number | null
  policyMode: TelemetryPolicyMode
  policyExpiresAt: number | null
}

export interface TelemetryPolicyItem {
  policyId: string
  uid: string
  deviceId: string | null
  mode: TelemetryPolicyMode
  reason: string | null
  revision: number
  expiresAt: number | null
  updatedAt: number
  updatedBy: string
  active: boolean
}

export const TELEMETRY_PAGE_SIZE = 50
export const TELEMETRY_SEARCH_WINDOW = 10_000

export function shortIdentity(value: string) {
  if (!value) return '—'
  return value.length > 12 ? `${value.slice(0, 12)}…` : value
}

export function exactTime(value?: number | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—'
}
