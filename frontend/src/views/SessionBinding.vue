<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElButton } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import type { Equipment, SessionEquipment, Session, EquipmentStatus } from '@/types'
import { AGE_GROUP_MAP, EQUIPMENT_STATUS_MAP } from '@/types'
import { equipmentApi, sessionApi, sessionEquipmentApi } from '@/api'

const route = useRoute()
const router = useRouter()
const sessionId = Number(route.params.id)

const session = ref<Session | null>(null)
const allEquipments = ref<Equipment[]>([])
const sessionEquipments = ref<SessionEquipment[]>([])

const boundIds = computed(() => new Set(sessionEquipments.value.map(se => se.equipmentId)))

/** 可绑定清单：仅资产状态为"可用/使用中"的器材出现；送检中、已报废、维护中从清单消失 */
const availableEquipments = computed(() =>
  allEquipments.value.filter(
    e => !boundIds.value.has(e.id) && (e.status === 'AVAILABLE' || e.status === 'IN_USE')
  )
)

const excludedCount = computed(
  () => allEquipments.value.filter(e => !boundIds.value.has(e.id) && e.status !== 'AVAILABLE' && e.status !== 'IN_USE').length
)

const boundEquipmentDetails = computed(() => {
  const equipmentMap = new Map(allEquipments.value.map(e => [e.id, e]))
  return sessionEquipments.value.map(se => ({
    ...se,
    equipment: equipmentMap.get(se.equipmentId)
  }))
})

const statusTagType = (status?: EquipmentStatus) =>
  status === 'AVAILABLE'
    ? 'success'
    : status === 'IN_USE'
      ? 'warning'
      : status === 'INSPECTION'
        ? 'danger'
        : status === 'SCRAPPED'
          ? 'info'
          : 'info'

const loadData = async () => {
  const [s, all, bound] = await Promise.all([
    sessionApi.getById(sessionId),
    equipmentApi.getAll(),
    sessionEquipmentApi.getBySession(sessionId)
  ])
  session.value = s
  allEquipments.value = all
  sessionEquipments.value = bound
}

onMounted(loadData)

const handleBind = async (equipmentId: number) => {
  const equipment = allEquipments.value.find(e => e.id === equipmentId)
  if (!equipment) return
  try {
    await sessionEquipmentApi.bind(sessionId, equipmentId, equipment.ageGroup)
    ElMessage.success('绑定成功')
  } catch (e: any) {
    // 旧页面停留期间器材被送检/报废：后端 409 给出明确提示，不覆盖新状态
    ElMessage({ type: e?.response?.status === 409 ? 'warning' : 'error', message: e?.response?.data?.error || '绑定失败' })
  } finally {
    await loadData()
  }
}

const handleUnbind = async (equipmentId: number) => {
  try {
    await sessionEquipmentApi.unbind(sessionId, equipmentId)
    ElMessage.success('解绑成功')
  } catch (e: any) {
    ElMessage({ type: 'error', message: e?.response?.data?.error || '解绑失败' })
  }
  loadData()
}

const handleAutoBind = async () => {
  try {
    await sessionEquipmentApi.autoBind(sessionId)
    ElMessage.success('自动绑定完成（送检中/已报废器材已自动排除）')
  } catch (e: any) {
    ElMessage({ type: 'error', message: e?.response?.data?.error || '自动绑定失败' })
  }
  loadData()
}

const goBack = () => {
  router.push('/session')
}
</script>

<template>
  <div class="session-binding">
    <div class="header">
      <el-button @click="goBack">返回</el-button>
      <h2>{{ session?.sessionName }} - 器材绑定</h2>
      <el-button type="primary" @click="handleAutoBind">自动绑定</el-button>
    </div>

    <el-alert
      v-if="excludedCount > 0"
      class="mb"
      type="info"
      :closable="false"
      :title="`有 ${excludedCount} 件器材因送检中 / 已报废 / 维护中未出现在可绑定清单中`"
      show-icon
    />

    <div class="content">
      <div class="panel">
        <h3>可绑定器材（仅资产可用）</h3>
        <el-table :data="availableEquipments" border empty-text="暂无可绑定器材（送检中/已报废器材不会出现）">
          <el-table-column prop="equipmentCode" label="器材编号" />
          <el-table-column prop="name" label="器材名称" />
          <el-table-column prop="ageGroup" label="适配年龄段">
            <template #default="scope">
              {{ AGE_GROUP_MAP[scope.row.ageGroup as keyof typeof AGE_GROUP_MAP]?.label }}
            </template>
          </el-table-column>
          <el-table-column prop="category" label="类别" />
          <el-table-column label="资产状态" width="100">
            <template #default="scope">
              <el-tag size="small" :type="statusTagType(scope.row.status as EquipmentStatus)">
                {{ EQUIPMENT_STATUS_MAP[scope.row.status as keyof typeof EQUIPMENT_STATUS_MAP] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作">
            <template #default="scope">
              <el-button size="small" type="primary" @click="handleBind((scope.row as Equipment).id)">绑定</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <h3>已绑定器材</h3>
        <el-table :data="boundEquipmentDetails" border>
          <el-table-column label="器材编号">
            <template #default="scope">{{ scope.row.equipment?.equipmentCode }}</template>
          </el-table-column>
          <el-table-column label="器材名称">
            <template #default="scope">{{ scope.row.equipment?.name }}</template>
          </el-table-column>
          <el-table-column prop="targetAgeGroup" label="绑定年龄段">
            <template #default="scope">
              {{ AGE_GROUP_MAP[scope.row.targetAgeGroup as keyof typeof AGE_GROUP_MAP]?.label }}
            </template>
          </el-table-column>
          <el-table-column label="资产状态" width="100">
            <template #default="scope">
              <el-tag
                size="small"
                :type="statusTagType((scope.row.equipment?.status) as EquipmentStatus)"
              >
                {{ scope.row.equipment
                  ? EQUIPMENT_STATUS_MAP[scope.row.equipment.status as keyof typeof EQUIPMENT_STATUS_MAP]
                  : '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作">
            <template #default="scope">
              <el-button size="small" type="danger" @click="handleUnbind(scope.row.equipmentId)">解绑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.session-binding {
  background: white;
  border-radius: 8px;
  padding: 20px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.header h2 {
  margin: 0;
}

.content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.panel {
  background: #fafafa;
  border-radius: 8px;
  padding: 15px;
}

.panel h3 {
  margin: 0 0 15px 0;
  font-size: 16px;
}

.mb {
  margin-bottom: 14px;
}
</style>
