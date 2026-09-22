# wx-1010 冰雪乐园装备与场次绑定系统

## 项目简介

冰雪乐园装备与场次绑定系统，包含 Spring Boot 后端、Vue/Vite 前端、MySQL 和 Redis。项目已统一为 UTF-8 编码，并通过 Docker Compose 固定端口交付。

## 端口

- 前端: http://localhost:3210 / http://127.0.0.1:3210
- 后端 API: http://localhost:3310/api
- MySQL: 127.0.0.1:3410
- Redis: 127.0.0.1:6510

## 构建与启动

```bash
cd /Users/Admin/Desktop/solo-0601/wx-0701/wx-组1/wx-1010
cd backend && mvn compile -q
cd ../frontend && npm ci && npm run build
cd .. && docker compose up -d --build
```

也可以执行：

```bash
./start.sh
```

## Docker 构建缓存

- 后端 Dockerfile 先复制 `pom.xml` 和 `settings.xml` 并下载 Maven 依赖，再复制 `src` 编译。
- 前端 Dockerfile 先复制 `package.json` 和 `package-lock.json` 并安装 npm 依赖，再复制源码执行构建。
- `.dockerignore` 排除了 `node_modules`、`dist`、`target`、日志、临时文件、截图和 IDE 配置。

## 入场发装台（现场发装/归还闭环）

独立操作页：前端菜单「入场发装台」（路由 `/dispatch`，支持 `?sessionId=` 预选场次），也可在「场次管理」行内点「入场发装」进入。

覆盖「发出去—用着—还回来」完整链路，并发与状态约束全部在后端/数据库保证：

1. **同一器材同一时刻只能发给一位游客**：发装先对场次行加悲观写锁串行化同场次操作，再对绑定关系做条件 UPDATE（CAS，仅"在架"可置"已领用"，影响行数 0 即失败），流水表 `outstanding_key` 唯一索引做跨场次兜底。并发领用只有一人成功，其他人收到 HTTP 409「已被其他工作人员领用」。
2. **年龄段 + 气温双校验**：游客年龄段须与器材在本场次的目标年龄段匹配，实测气温不得低于从抗冻规格文本解析出的下限；两条同时校验，任一不满足都会在错误信息中具体指出（两条同时不满足会分别列出）。实测气温等于下限（临界值）允许发装。
3. **场次时序与结束兜底**：只有"进行中"场次可发装；场次结束（专用接口或编辑改状态）在同一事务内把所有"已领用未归还"流水置为"结束兜底收回"、绑定状态复位在架、器材资产状态复位可用，不会出现场次结束后器材悬空、无法归还或无法再绑定的情况。
4. **留痕**：`equipment_dispatch_record` 记录每次发装的器材、游客、游客年龄段、实测气温、抗冻下限、发装人、发装时间，以及归还/兜底的操作人与时间；可按场次查询完整流水。归还后器材立即可重新领用。

接口（均在 `/api/session/{sessionId}/dispatch` 下）：

- `GET /items`：现场视图（绑定器材 + 当前发装状态 + 当前使用游客 + 资产/送检状态）
- `GET /records`：按场次查发装/归还流水
- `POST /issue`：发装（请求体含 equipmentId、visitorName、visitorAgeGroup、temperature、operator）
- `POST /records/{recordId}/return`：归还（请求体含 operator）
- 场次状态：`POST /api/session/{id}/start`、`POST /api/session/{id}/end`

后端测试：`cd backend && mvn test`（含 8 线程并发领用、并发归还、临界气温、双校验、场次结束兜底、兜底后新场次重新领用等 17 个用例，使用 H2 MySQL 模式验证真实数据库锁与唯一约束行为）。

## 器材送检台（送检—维修—复检—报废—重新可用）

独立操作页：前端菜单「器材送检台」（路由 `/inspection`，器材管理页「送检」按钮可带参直达登记弹窗）。

资产状态在原有 可用/使用中/维护中 基础上新增：**送检中 INSPECTION**、**已报废 SCRAPPED**。送检单状态机：

```
登记送检 ──器材在架──▶ 待维修 SUBMITTED ──提交复检──▶ 复检中 REINSPECTING ──通过──▶ 复检通过 PASSED（回可用池）
  │                       │  ▲                            │
  │                  退回补材料│  │补充后重提              不通过（退回维修，留痕）
  │                       ▼  │
  │                  待补充材料 INFO_NEEDED
  │
  └──器材已发游客──▶ 待归还 PENDING_RETURN ──游客正常归还 / 场次结束兜底 / 显式转入待处理──▶ 待维修
                         任意在库维修阶段 ──判定报废──▶ 已报废 SCRAPPED
```

关键约束（全部在后端 + 数据库保证，不依赖前端禁用按钮）：

1. **同一器材至多一张未关闭送检单**：登记时先对器材行加悲观写锁串行化，`inspection_order.open_key`（`inspect:{equipmentId}`，关闭置 NULL）唯一索引兜底；并发送检只有一张成功，其余收到 409「不能重复送检」。
2. **送检即冻结**：器材置送检中、全部场次绑定行 CAS 置「送检冻结 PENDING」，不能再被新场次绑定或发装；发装/绑定都持器材行锁读最新状态，旧页面停留期间器材被送检/报废时提交只会收到 409 明确提示，不覆盖新状态、不破坏原有流水。
3. **不悬空**：已发给游客的器材允许先登记（待归还 PENDING_RETURN）——游客可按**原流水正常归还**（归还时单据自动进维修队列、绑定行重新冻结）；场次结束兜底同样推进单据；游客确认无法归还时可「转入待处理」，发装流水置 `PENDING_TRANSFER` 闭环并释放未归还互斥键，不存在既不能归还又不能维修的记录。
4. **历史不覆盖**：每次状态变化向 `inspection_action_log` 追加一条痕迹（操作人、前后状态、备注、关联流水）；问题描述创建后不可改，补充材料以痕迹追加；复检不通过退回维修可再次复检；复检通过后再出问题另开新单，旧单完整保留。
5. **报废**：必须填报废原因；报废器材保留全部送检/发装历史，但从可绑定清单、自动绑定和发装中消失，且不能再送检；有历史流水/送检单的器材禁止物理删除。资产状态不能通过器材编辑表单直接修改。
6. **复检通过重新放行**：冻结的绑定行恢复在架；器材仍被场次绑定则资产置使用中，否则复位可用，立即恢复绑定/发装资格。

接口（均在 `/api/inspection` 下）：

- `GET /orders?equipmentId=&status=&openOnly=`：送检单查询（默认未关闭）
- `GET /orders/{id}`：送检单详情（含完整操作痕迹）
- `POST /equipment/{equipmentId}`：登记送检（problemDescription、reporter）
- `POST /orders/{id}/transfer-pending`：转入待处理（handler、remark）
- `POST /orders/{id}/request-info`：退回补充材料（handler、remark 必填）
- `POST /orders/{id}/resubmit`：补充材料后重新提交（handler、remark 必填）
- `POST /orders/{id}/submit-reinspection`：提交复检（handler、remark 选填）
- `POST /orders/{id}/reinspection-pass`：复检通过放行（handler、remark 选填）
- `POST /orders/{id}/reinspection-fail`：复检不通过（handler、remark 必填）
- `POST /orders/{id}/scrap`：判定报废（handler、remark 必填）

送检台后端测试：`InspectionWorkflowIntegrationTest`（14 个用例：重复送检并发、维修退回补充材料、复检不通过再复检、报废后不可发装/绑定/再送检、待归还直接报废拦截、送检期间正常归还、转入待处理、场次结束兜底联动、旧页面迟到操作 409、复检后重新送检新单、多页面状态一致性、open_key 唯一索引拦截、痕迹查询）。


