export type AgeGroup = 'CHILD' | 'TEEN' | 'ADULT'

export type EquipmentStatus = 'AVAILABLE' | 'IN_USE' | 'MAINTENANCE' | 'INSPECTION' | 'SCRAPPED'

export type SessionStatus = 'SCHEDULED' | 'IN_PROGRESS' | 'ENDED'

/** 场次-器材绑定的现场发装状态 */
export type BindDispatchStatus = 'AVAILABLE' | 'ISSUED' | 'PENDING'

/** 发装流水状态：已领用 / 已归还 / 场次结束兜底收回 / 转入待处理 */
export type DispatchStatus = 'ISSUED' | 'RETURNED' | 'AUTO_CLOSED' | 'PENDING_TRANSFER'

/** 送检单状态 */
export type InspectionStatus =
  | 'PENDING_RETURN'
  | 'SUBMITTED'
  | 'INFO_NEEDED'
  | 'REINSPECTING'
  | 'PASSED'
  | 'SCRAPPED'

export type InspectionActionType =
  | 'CREATE'
  | 'TRANSFER_PENDING'
  | 'RETURN_WHILE_PENDING'
  | 'SESSION_AUTO_CLOSE'
  | 'RESUBMIT'
  | 'REQUEST_INFO'
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
  equipmentStatus?: EquipmentStatus
  equipmentStatusLabel?: string
  inspectionOrderId?: number | null
  inspectionStatusLabel?: string
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

/** 送检单操作痕迹 */
export interface InspectionActionLog {
  id: number
  orderId: number
  equipmentId: number
  actionType: InspectionActionType
  actionTypeLabel: string
  fromStatus: InspectionStatus | null
  fromStatusLabel?: string | null
  toStatus: InspectionStatus
  toStatusLabel: string
  operator: string
  remark?: string
  dispatchRecordId?: number | null
  actionTime: string
}

/** 器材送检单 */
export interface InspectionOrder {
  id: number
  equipmentId: number
  equipmentCode?: string
  equipmentName?: string
  category?: string
  problemDescription: string
  reporter: string
  reportTime: string
  handler?: string
  status: InspectionStatus
  statusLabel: string
  open: boolean
  dispatchRecordId?: number | null
  sessionId?: number | null
  conclusion?: string
  closeTime?: string
  createTime?: string
  updateTime?: string
  actionLogs?: InspectionActionLog[]
}

export interface IssueRequest {
  equipmentId: number
  visitorName: string
  visitorAgeGroup: AgeGroup
  temperature: number
  operator: string
}

export interface InspectionCreateRequest {
  problemDescription: string
  reporter: string
}

export interface InspectionActionRequest {
  handler: string
  remark?: string
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
  PENDING: '送检冻结'
}

export const DISPATCH_STATUS_MAP: Record<DispatchStatus, string> = {
  ISSUED: '已领用',
  RETURNED: '已归还',
  AUTO_CLOSED: '结束兜底收回',
  PENDING_TRANSFER: '转入待处理'
}

export const INSPECTION_STATUS_MAP: Record<InspectionStatus, { label: string; type: 'info' | 'warning' | 'danger' | 'primary' | 'success' }> = {
  PENDING_RETURN: { label: '待归还', type: 'warning' },
  SUBMITTED: { label: '待维修', type: 'primary' },
  INFO_NEEDED: { label: '待补充材料', type: 'warning' },
  REINSPECTING: { label: '复检中', type: 'primary' },
  PASSED: { label: '复检通过', type: 'success' },
  SCRAPPED: { label: '已报废', type: 'danger' }
}
