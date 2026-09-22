<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElButton } from 'element-plus'
import type { Equipment, AgeGroup, EquipmentStatus } from '@/types'
import { AGE_GROUP_MAP, EQUIPMENT_STATUS_MAP } from '@/types'
import { equipmentApi } from '@/api'
import AgeGroupFilter from '@/components/AgeGroupFilter.vue'
import EquipmentForm from '@/components/EquipmentForm.vue'

const router = useRouter()
const equipments = ref<Equipment[]>([])
const filterAgeGroup = ref<AgeGroup | ''>('')
const filterStatus = ref<EquipmentStatus | ''>('')
const showForm = ref(false)
const editingEquipment = ref<Equipment | null>(null)

const loadEquipments = async () => {
  const list = await equipmentApi.getAll(filterAgeGroup.value || undefined)
  equipments.value = filterStatus.value
    ? list.filter(e => e.status === filterStatus.value)
    : list
}

onMounted(loadEquipments)

const handleFilterChange = (value: AgeGroup | '' | AgeGroup[]) => {
  filterAgeGroup.value = typeof value === 'string' ? value : value[0] || ''
  loadEquipments()
}

const statusOptions = Object.entries(EQUIPMENT_STATUS_MAP).map(([value, label]) => ({ value, label }))

const statusTagType = (status: EquipmentStatus) =>
  ({ AVAILABLE: 'success', IN_USE: 'warning', MAINTENANCE: 'info', INSPECTION: 'danger', SCRAPPED: 'info' }[status] || 'info')

const handleAdd = () => {
  editingEquipment.value = null
  showForm.value = true
}

const handleEdit = (equipment: Equipment) => {
  editingEquipment.value = equipment
  showForm.value = true
}

const handleDelete = async (id: number) => {
  try {
    await equipmentApi.delete(id)
    ElMessage.success('删除成功')
    loadEquipments()
  } catch (e: any) {
    ElMessage({ type: 'error', message: e?.response?.data?.error || '删除失败' })
  }
}

const handleSubmit = async (data: Omit<Equipment, 'id'>) => {
  try {
    if (editingEquipment.value) {
      await equipmentApi.update(editingEquipment.value.id, data)
      ElMessage.success('更新成功')
    } else {
      await equipmentApi.create(data)
      ElMessage.success('创建成功')
    }
    showForm.value = false
    loadEquipments()
  } catch (e: any) {
    ElMessage({ type: 'error', message: e?.response?.data?.error || '保存失败' })
  }
}

const handleCancel = () => {
  showForm.value = false
}

// 送检：跳送检台并预选该器材（通过 query 触发登记弹窗）
const handleInspect = (equipment: Equipment) => {
  router.push({ path: '/inspection', query: { equipmentId: String(equipment.id), action: 'create' } })
}

const canInspect = (e: Equipment) => e.status !== 'SCRAPPED' && e.status !== 'INSPECTION'
</script>

<template>
  <div class="equipment-management">
    <div class="header">
      <h2>低温游乐器材管理</h2>
      <div class="actions">
        <AgeGroupFilter v-model="filterAgeGroup" @update:modelValue="handleFilterChange" />
        <el-select v-model="filterStatus" placeholder="全部资产状态" clearable style="width: 140px" @change="loadEquipments">
          <el-option v-for="o in statusOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" @click="handleAdd">新增器材</el-button>
      </div>
    </div>

    <el-table :data="equipments" border>
      <el-table-column prop="equipmentCode" label="器材编号" />
      <el-table-column prop="name" label="器材名称" />
      <el-table-column prop="frostResistanceSpec" label="耐寒规格" />
      <el-table-column prop="ageGroup" label="适配年龄段">
        <template #default="scope">
          <span>{{ AGE_GROUP_MAP[scope.row.ageGroup as keyof typeof AGE_GROUP_MAP]?.label }} ({{ AGE_GROUP_MAP[scope.row.ageGroup as keyof typeof AGE_GROUP_MAP]?.ageRange }})</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="器材类别" />
      <el-table-column prop="status" label="资产状态">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status as EquipmentStatus)">
            {{ EQUIPMENT_STATUS_MAP[scope.row.status as keyof typeof EQUIPMENT_STATUS_MAP] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260">
        <template #default="scope">
          <el-button size="small" @click="handleEdit(scope.row as Equipment)">编辑</el-button>
          <el-button
            size="small"
            type="warning"
            :disabled="!canInspect(scope.row as Equipment)"
            @click="handleInspect(scope.row as Equipment)"
          >送检</el-button>
          <el-button size="small" type="danger" @click="handleDelete((scope.row as Equipment).id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <EquipmentForm :equipment="editingEquipment" :visible="showForm" @submit="handleSubmit" @cancel="handleCancel" />
  </div>
</template>

<style scoped>
.equipment-management {
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

.actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
</style>
