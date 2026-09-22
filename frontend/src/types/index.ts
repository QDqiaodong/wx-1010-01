export type AgeGroup = 'CHILD' | 'TEEN' | 'ADULT'

export type EquipmentStatus = 'AVAILABLE' | 'IN_USE' | 'MAINTENANCE' | 'INSPECTION' | 'SCRAPPED'

export type SessionStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'ENDED'

/** 场次-器材绑定的现场发装状态 */
export type BindDispatchStatus = 'AVAILABLE' | 'ISSUED' | 'QUARANTINED' | 'SCRAPPED'

/** 发装流水状态：已领用 / 已归还 / 场次结束兜底收回 / 送检时转入待处理 */
export type DispatchStatus = 'ISSUED' | 'RETURNED' | 'AUTO_CLOSED' | 'TRANSFERRED_PENDING'

/** 送检单状态：待维修 / 待补充材料 / 复检中 / 复检通过关闭 / 报废关闭 */
export type InspectionStatus =
  | 'SUBMITTED'
  | 'MATERIAL_NEEDED'
  | 'REINSPECTING'
  | 'CLOSED_PASSED'
  | 'CLOSED_SCRAPPED'

/** 送检单上的操作（每次操作留痕，历史只追加） */
export type InspectionAction =
  | 'SUBMIT'
  | 'RETURN_MATERIALS'
  | 'RESUBMIT'
  | 'SUBMIT_REINSPECTION'
  | 'REINSPECTION_PASS'
  | 'REINSPECTION_FAIL'
  | 'SCRAP'

export interface Equipment {
  id: number
  equipmentCode: string
  name: string
  frostResistanceSpec: string
  ageGroup: AgeGroup
  category: string
  status: EquipmentStatus
  statusLabel?: string
  /** 当前未关闭送检单ID（无则 null/undefined） */
  openInspectionId?: number | null
  createTime?: string
  updateTime?: string
}

export interface Session {
  id: number
  sessionCode: string
  sessionName: string
  startTime: string
  endTime: string
  childRatio: number
  teenRatio: number
  adultRatio: number
  status: SessionStatus
}

export interface SessionEquipment {
  id: number
  sessionId: number
  equipmentId: number
  targetAgeGroup: AgeGroup
}

export interface AdjustRecord {
  id: number
  sessionId: number
  equipmentId: number
  oldAgeGroup: AgeGroup
  newAgeGroup: AgeGroup
  adjustReason?: string
  adjustOperator: string
  adjustTime?: string
}

export interface AgeGroupSummary {
  ageGroup: AgeGroup
  ageGroupLabel: string
  ageRange: string
  totalCount: number
  equipments: Equipment[]
}

/** 发装台现场视图：场次绑定的一件器材及其当前发装状态 */
export interface SessionDispatchItem {
  sessionEquipmentId: number
  equipmentId: number
  equipmentCode: string
  equipmentName: string
  category: string
  frostResistanceSpec: string
  targetAgeGroup: AgeGroup
  targetAgeGroupLabel: string
  frostLowerLimit: number | null
  dispatchStatus: BindDispatchStatus
  dispatchStatusLabel: string
  /** 器材资产级状态（送检中/已报废时现场页据此提示并禁止发装） */
  equipmentStatus?: EquipmentStatus
  equipmentStatusLabel?: string
  openInspectionId?: number | null
  activeRecordId: number | null
  activeVisitorName?: string
  activeVisitorAgeGroup?: AgeGroup
  activeVisitorAgeGroupLabel?: string
}

/** 发装/归还流水 */
export interface EquipmentDispatchRecord {
  id: number
  sessionId: number
  sessionEquipmentId: number
  equipmentId: number
  equipmentCode?: string
  equipmentName?: string
  frostResistanceSpec?: string
  visitorName: string
  visitorAgeGroup: AgeGroup
  visitorAgeGroupLabel: string
  temperatureAtIssue: number
  frostLowerLimitAtIssue: number
  issueOperator: string
  issueTime: string
  status: DispatchStatus
  statusLabel: string
  returnOperator?: string
  returnTime?: string
}

export interface IssueRequest {
  equipmentId: number
  visitorName: string
  visitorAgeGroup: AgeGroup
  temperature: number
  operator: string
}

export const AGE_GROUP_MAP: Record<AgeGroup, { label: string; ageRange: string }> = {
  CHILD: { label: '幼童', ageRange: '3-7岁' },
  TEEN: { label: '青少年', ageRange: '8-17岁' },
  ADULT: { label: '成人', ageRange: '18岁以上' }
}

export const EQUIPMENT_STATUS_MAP: Record<EquipmentStatus, string> = {
  AVAILABLE: '可用',
  IN_USE: '使用中',
  MAINTENANCE: '维护中',
  INSPECTION: '送检中',
  SCRAPPED: '已报废'
}

export const SESSION_STATUS_MAP: Record<SessionStatus, string> = {
  SCHEDULED: '已安排',
  IN_PROGRESS: '进行中',
  ENDED: '已结束'
}

export const BIND_DISPATCH_STATUS_MAP: Record<BindDispatchStatus, string> = {
  AVAILABLE: '在架',
  ISSUED: '已领用',
  QUARANTINED: '送检隔离',
  SCRAPPED: '已报废留档'
}

export const DISPATCH_STATUS_MAP: Record<DispatchStatus, string> = {
  ISSUED: '已领用',
  RETURNED: '已归还',
  AUTO_CLOSED: '结束兜底收回',
  TRANSFERRED_PENDING: '转入待处理'
}

export const INSPECTION_STATUS_MAP: Record<InspectionStatus, string> = {
  SUBMITTED: '待维修',
  MATERIAL_NEEDED: '待补充材料',
  REINSPECTING: '复检中',
  CLOSED_PASSED: '复检通过关闭',
  CLOSED_SCRAPPED: '报废关闭'
}

export const INSPECTION_ACTION_MAP: Record<InspectionAction, string> = {
  SUBMIT: '提交送检',
  RETURN_MATERIALS: '退回补充材料',
  RESUBMIT: '材料补齐重新提交',
  SUBMIT_REINSPECTION: '维修完成提交复检',
  REINSPECTION_PASS: '复检通过',
  REINSPECTION_FAIL: '复检不通过',
  SCRAP: '判定报废'
}

// ---------- 送检台 ----------

export interface InspectionOrder {
  id: number
  equipmentId: number
  equipmentCode?: string
  equipmentName?: string
  reporter: string
  problemDescription: string
  submitTime: string
  status: InspectionStatus
  statusLabel: string
  handler?: string
  conclusion?: string
  updateTime?: string
  eventCount?: number
}

export interface InspectionEvent {
  id: number
  inspectionOrderId: number
  equipmentId: number
  action: InspectionAction
  actionLabel: string
  fromStatus?: InspectionStatus | null
  fromStatusLabel?: string | null
  toStatus: InspectionStatus
  toStatusLabel: string
  operator: string
  note?: string
  eventTime: string
}

export interface InspectionOrderDetail {
  order: InspectionOrder
  equipmentStatus?: EquipmentStatus
  equipmentStatusLabel?: string
  events: InspectionEvent[]
}

export interface InspectionCreateRequest {
  equipmentId: number
  reporter: string
  problemDescription: string
  forceTransfer?: boolean
}

export interface InspectionActionRequest {
  handler: string
  note?: string
  scrapOnFail?: boolean
}