<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type {
  Equipment,
  InspectionOrder,
  InspectionOrderDetail,
  InspectionStatus
} from '@/types'
import { equipmentApi, inspectionApi, apiError } from '@/api'

const route = useRoute()

// ---------- 列表与筛选 ----------
const tab = ref<'open' | 'closed' | 'all'>('open')
const orders = ref<InspectionOrder[]>([])
const loading = ref(false)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const loadOrders = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    orders.value = await inspectionApi.list(tab.value === 'all' ? undefined : tab.value === 'open')
  } catch (e) {
    ElMessage.error(apiError(e, '送检单加载失败'))
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadOrders()
  // 多人协作：每 5 秒静默刷新，保证停留期间状态一致（与发装台一致）
  refreshTimer = setInterval(() => loadOrders(true), 5000)

  // 从器材列表"送检"按钮跳转过来时，自动打开已预选器材的送检弹窗
  const preselect = Number(route.query.equipmentId)
  if (Number.isFinite(preselect) && preselect > 0) {
    await openSubmitDialog(preselect)
  }
})
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})
watch(tab, () => loadOrders())

const statusTagType = (s: InspectionStatus) => ({
  SUBMITTED: 'warning',
  MATERIAL_NEEDED: 'danger',
  REINSPECTING: 'primary',
  CLOSED_PASSED: 'success',
  CLOSED_SCRAPPED: 'info'
}[s])

const fmtTime = (t?: string) => (t ? t.replace('T', ' ').slice(0, 19) : '—')

// ---------- 新增送检 ----------
const equipments = ref<Equipment[]>([])
const submitDialogVisible = ref(false)
const submitting = ref(false)
const submitOperator = ref(localStorage.getItem('inspection_reporter') || '')
const submitForm = ref<{ equipmentId: number | null; problemDescription: string; forceTransfer: boolean }>({
  equipmentId: null,
  problemDescription: '',
  forceTransfer: false
})

const selectableEquipments = computed(() =>
  equipments.value.filter(e => e.status !== 'SCRAPPED' && !e.openInspectionId)
)

const selectedEquipment = computed(() =>
  equipments.value.find(e => e.id === submitForm.value.equipmentId) ?? null
)

const openSubmitDialog = async (equipmentId?: number) => {
  submitForm.value = {
    equipmentId: equipmentId ?? null,
    problemDescription: '',
    forceTransfer: false
  }
  try {
    equipments.value = await equipmentApi.getAll()
  } catch (e) {
    ElMessage.error(apiError(e, '器材列表加载失败'))
  }
  submitDialogVisible.value = true
}

watch(submitOperator, v => localStorage.setItem('inspection_reporter', v.trim()))

const handleSubmitInspection = async () => {
  if (!submitForm.value.equipmentId) {
    ElMessage.error('请选择要送检的器材')
    return
  }
  if (!submitOperator.value.trim()) {
    ElMessage.error('请填写发现人')
    return
  }
  if (!submitForm.value.problemDescription.trim()) {
    ElMessage.error('请填写问题描述')
    return
  }
  submitting.value = true
  try {
    const detail = await inspectionApi.submit({
      equipmentId: submitForm.value.equipmentId,
      reporter: submitOperator.value.trim(),
      problemDescription: submitForm.value.problemDescription.trim(),
      forceTransfer: submitForm.value.forceTransfer
    })
    const transferred = detail.events?.some(ev => ev.note?.includes('转入待处理'))
    ElMessage.success(
      transferred
        ? `送检单 #${detail.order.id} 已创建，器材已从游客处转入待处理并进入维修流程`
        : `送检单 #${detail.order.id} 已创建`
    )
    submitDialogVisible.value = false
    await loadOrders()
  } catch (e: any) {
    const status = e?.response?.status
    ElMessage({
      type: status === 409 ? 'warning' : 'error',
      message: apiError(e, '送检失败'),
      duration: 6000
    })
  } finally {
    submitting.value = false
  }
}

// ---------- 维修侧操作 ----------
const actionDialogVisible = ref(false)
const actionSubmitting = ref(false)
const actionTarget = ref<InspectionOrder | null>(null)
const actionKind = ref<'returnMaterials' | 'resubmit' | 'submitReinspection' | 'pass' | 'fail' | 'scrap'>('submitReinspection')
const actionHandler = ref(localStorage.getItem('inspection_handler') || '')
const actionNote = ref('')

watch(actionHandler, v => localStorage.setItem('inspection_handler', v.trim()))

const ACTION_META = {
  returnMaterials: { title: '退回补充材料', label: '确认退回', required: true, danger: false },
  resubmit: { title: '材料补齐重新提交', label: '确认提交', required: false, danger: false },
  submitReinspection: { title: '维修完成 · 提交复检', label: '提交复检', required: false, danger: false },
  pass: { title: '复检通过 · 重新放行', label: '确认通过', required: false, danger: false },
  fail: { title: '复检不通过 · 退回维修', label: '登记不通过', required: true, danger: true },
  scrap: { title: '判定报废', label: '确认报废', required: true, danger: true }
} as const

const openAction = (order: InspectionOrder, kind: typeof actionKind.value) => {
  actionTarget.value = order
  actionKind.value = kind
  actionNote.value = ''
  actionDialogVisible.value = true
}

const handleAction = async () => {
  if (!actionTarget.value) return
  const meta = ACTION_META[actionKind.value]
  if (!actionHandler.value.trim()) {
    ElMessage.error('请填写处理人')
    return
  }
  if (meta.required && !actionNote.value.trim()) {
    ElMessage.error(actionKind.value === 'returnMaterials' ? '请填写需要补充的材料说明' : '请填写处理说明/理由')
    return
  }
  const id = actionTarget.value.id
  const payload = { handler: actionHandler.value.trim(), note: actionNote.value.trim() }
  actionSubmitting.value = true
  try {
    let detail: InspectionOrderDetail
    switch (actionKind.value) {
      case 'returnMaterials':
        detail = await inspectionApi.returnMaterials(id, payload)
        break
      case 'resubmit':
        detail = await inspectionApi.resubmit(id, payload)
        break
      case 'submitReinspection':
        detail = await inspectionApi.submitReinspection(id, payload)
        break
      case 'pass':
        detail = await inspectionApi.passReinspection(id, payload)
        break
      case 'fail':
        detail = await inspectionApi.failReinspection(id, payload)
        break
      case 'scrap':
        detail = await inspectionApi.scrap(id, payload)
        break
    }
    ElMessage.success(`送检单 #${id} 当前状态：${detail.order.statusLabel}`)
    actionDialogVisible.value = false
    if (detailDrawerVisible.value && detailDrawer.value?.order.id === id) {
      detailDrawer.value = detail
    }
    await loadOrders()
  } catch (e: any) {
    ElMessage({
      type: e?.response?.status === 409 ? 'warning' : 'error',
      message: apiError(e, '操作失败'),
      duration: 6000
    })
  } finally {
    actionSubmitting.value = false
  }
}

// ---------- 详情 / 操作痕迹 ----------
const detailDrawerVisible = ref(false)
const detailDrawer = ref<InspectionOrderDetail | null>(null)
const detailLoading = ref(false)

const openDetail = async (order: InspectionOrder) => {
  detailDrawerVisible.value = true
  detailLoading.value = true
  try {
    detailDrawer.value = await inspectionApi.getDetail(order.id)
  } catch (e) {
    ElMessage.error(apiError(e, '详情加载失败'))
  } finally {
    detailLoading.value = false
  }
}
</script>

<template>
  <div class="inspection-desk">
    <div class="header">
      <h2>器材送检台</h2>
      <div class="actions">
        <el-button @click="loadOrders()">刷新</el-button>
        <el-button type="primary" @click="openSubmitDialog()">新增送检</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mb"
      title="送检后器材立即退出新场次绑定与现场发装；已发给游客的可先归还或在送检时勾选「转入待处理」。维修可退回补材料或报废，复检通过才重新回到可用池，全部操作留痕可查。"
    />

    <el-tabs v-model="tab" class="mb">
      <el-tab-pane label="未关闭（待处理/复检中）" name="open" />
      <el-tab-pane label="已关闭（通过/报废）" name="closed" />
      <el-tab-pane label="全部记录" name="all" />
    </el-tabs>

    <el-table :data="orders" border v-loading="loading" empty-text="暂无送检单">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column label="器材" min-width="180">
        <template #default="{ row }">
          <div>{{ row.equipmentCode }}</div>
          <div class="muted">{{ row.equipmentName }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="reporter" label="发现人" width="100" />
      <el-table-column prop="problemDescription" label="问题描述" min-width="200" show-overflow-tooltip />
      <el-table-column label="送检时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.submitTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status as InspectionStatus)">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="handler" label="当前处理人" width="110">
        <template #default="{ row }">{{ row.handler || '—' }}</template>
      </el-table-column>
      <el-table-column prop="conclusion" label="当前结论" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.conclusion || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">痕迹</el-button>
          <template v-if="row.status === 'SUBMITTED'">
            <el-button size="small" @click="openAction(row, 'returnMaterials')">退补材料</el-button>
            <el-button size="small" type="primary" @click="openAction(row, 'submitReinspection')">提交复检</el-button>
            <el-button size="small" type="danger" plain @click="openAction(row, 'scrap')">报废</el-button>
          </template>
          <template v-else-if="row.status === 'MATERIAL_NEEDED'">
            <el-button size="small" type="primary" @click="openAction(row, 'resubmit')">补齐提交</el-button>
            <el-button size="small" type="danger" plain @click="openAction(row, 'scrap')">报废</el-button>
          </template>
          <template v-else-if="row.status === 'REINSPECTING'">
            <el-button size="small" type="success" @click="openAction(row, 'pass')">复检通过</el-button>
            <el-button size="small" type="warning" @click="openAction(row, 'fail')">不通过</el-button>
            <el-button size="small" type="danger" plain @click="openAction(row, 'scrap')">报废</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增送检弹窗 -->
    <el-dialog v-model="submitDialogVisible" title="器材送检登记" width="560px">
      <el-form label-width="110px">
        <el-form-item label="发现人" required>
          <el-input v-model="submitOperator" placeholder="工作人员姓名/工号" style="width: 260px" />
        </el-form-item>
        <el-form-item label="送检器材" required>
          <el-select
            v-model="submitForm.equipmentId"
            filterable
            placeholder="按编号/名称选择器材"
            style="width: 380px"
          >
            <el-option
              v-for="e in selectableEquipments"
              :key="e.id"
              :label="`${e.equipmentCode} ${e.name}（${e.statusLabel || e.status}）`"
              :value="e.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="selectedEquipment">
          <el-alert
            :type="selectedEquipment.status === 'IN_USE' ? 'warning' : 'info'"
            :closable="false"
            show-icon
            :title="selectedEquipment.status === 'IN_USE'
              ? '该器材已绑定场次：如已发给游客，可先按流水归还；若现场无法归还，勾选下方「转入待处理」先行收回。'
              : '提交后器材立即进入送检流程，不能再被场次绑定或发装。'"
          />
        </el-form-item>
        <el-form-item label="问题描述" required>
          <el-input
            v-model="submitForm.problemDescription"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="现场发现的异常现象，如：冰刀卡扣松动、温感报警等"
          />
        </el-form-item>
        <el-form-item label="转入待处理">
          <el-checkbox v-model="submitForm.forceTransfer">
            器材已发给游客且暂无法正常归还，先按当前流水转入待处理再送检
          </el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitInspection">提交送检</el-button>
      </template>
    </el-dialog>

    <!-- 维修侧操作弹窗 -->
    <el-dialog
      v-model="actionDialogVisible"
      :title="actionTarget ? `#${actionTarget.id} ${actionTarget.equipmentCode} · ${ACTION_META[actionKind].title}` : ''"
      width="520px"
    >
      <el-form label-width="90px" v-if="actionTarget">
        <el-form-item label="问题描述">
          <div class="problem-box">{{ actionTarget.problemDescription }}</div>
        </el-form-item>
        <el-form-item label="处理人" required>
          <el-input v-model="actionHandler" placeholder="维修/复检人员姓名或工号" />
        </el-form-item>
        <el-form-item
          :label="actionKind === 'returnMaterials' ? '补充要求'
            : actionKind === 'scrap' ? '报废理由'
            : actionKind === 'fail' ? '不通过原因'
            : '处理说明'"
          :required="ACTION_META[actionKind].required"
        >
          <el-input
            v-model="actionNote"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            :placeholder="actionKind === 'returnMaterials' ? '需要现场补充的照片/检测数据等'
              : actionKind === 'scrap' ? '报废判定依据，留档备查'
              : actionKind === 'fail' ? '复检不通过的具体问题，退回维修继续处理'
              : '可选，填写维修/复检结论'"
          />
        </el-form-item>
        <el-alert
          v-if="actionKind === 'scrap'"
          type="error"
          :closable="false"
          show-icon
          title="报废后器材永久退出可用池与可绑定清单，历史送检与发装记录保留，操作不可撤销。"
          class="mb"
        />
        <el-alert
          v-else-if="actionKind === 'pass'"
          type="success"
          :closable="false"
          show-icon
          title="复检通过后器材立即回到可用池：进行中场次的隔离绑定复位在架，可重新发装。"
        />
      </el-form>
      <template #footer>
        <el-button @click="actionDialogVisible = false">取消</el-button>
        <el-button
          :type="ACTION_META[actionKind].danger ? 'danger' : 'primary'"
          :loading="actionSubmitting"
          @click="handleAction"
        >{{ ACTION_META[actionKind].label }}</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉：完整操作痕迹 -->
    <el-drawer v-model="detailDrawerVisible" title="送检单详情与操作痕迹" size="560px">
      <div v-loading="detailLoading">
        <template v-if="detailDrawer">
          <el-descriptions :column="1" border class="mb">
            <el-descriptions-item label="送检单号">#{{ detailDrawer.order.id }}</el-descriptions-item>
            <el-descriptions-item label="器材">
              {{ detailDrawer.order.equipmentCode }} {{ detailDrawer.order.equipmentName }}
            </el-descriptions-item>
            <el-descriptions-item label="发现人">{{ detailDrawer.order.reporter }}</el-descriptions-item>
            <el-descriptions-item label="问题描述">{{ detailDrawer.order.problemDescription }}</el-descriptions-item>
            <el-descriptions-item label="送检时间">{{ fmtTime(detailDrawer.order.submitTime) }}</el-descriptions-item>
            <el-descriptions-item label="当前状态">
              <el-tag :type="statusTagType(detailDrawer.order.status)">{{ detailDrawer.order.statusLabel }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="器材资产状态">
              {{ detailDrawer.equipmentStatusLabel || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="当前处理人">{{ detailDrawer.order.handler || '—' }}</el-descriptions-item>
            <el-descriptions-item label="当前结论">{{ detailDrawer.order.conclusion || '—' }}</el-descriptions-item>
          </el-descriptions>

          <h4>操作痕迹（按时间顺序，历史不可覆盖）</h4>
          <el-timeline>
            <el-timeline-item
              v-for="ev in detailDrawer.events"
              :key="ev.id"
              :timestamp="`${fmtTime(ev.eventTime)} · ${ev.operator}`"
              :type="ev.action === 'REINSPECTION_PASS' ? 'success'
                : ev.action === 'SCRAP' || ev.action === 'REINSPECTION_FAIL' ? 'danger'
                : ev.action === 'RETURN_MATERIALS' ? 'warning'
                : 'primary'"
            >
              <div><b>{{ ev.actionLabel }}</b>
                <el-tag size="small" class="ml">
                  {{ ev.fromStatusLabel || '新建' }} → {{ ev.toStatusLabel }}
                </el-tag>
              </div>
              <div v-if="ev.note" class="muted mt">{{ ev.note }}</div>
            </el-timeline-item>
          </el-timeline>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.inspection-desk {
  background: white;
  border-radius: 8px;
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.header h2 {
  margin: 0;
}

.actions {
  display: flex;
  gap: 10px;
}

.mb {
  margin-bottom: 16px;
}

.ml {
  margin-left: 8px;
}

.mt {
  margin-top: 4px;
}

.muted {
  color: #909399;
  font-size: 12px;
}

.problem-box {
  background: #f7f9fc;
  border-radius: 4px;
  padding: 8px 12px;
  white-space: pre-wrap;
}
</style>
