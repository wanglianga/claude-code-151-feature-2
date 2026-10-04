# 城市图书馆跨馆借还调拨 · 逾期罚款与预约排队服务

一个城市级公共图书馆后端：读者可以检索本馆与各分馆库存、预约排队、跨馆调拨借书；馆员与物流员处理出库、到馆、还书核验、罚款赔偿与各类异常；管理端查看调拨效率、热门预约、逾期与丢损。

技术栈：Java 17 · Spring Boot 3.2 · Spring Data JPA · PostgreSQL 16 · Flyway · springdoc OpenAPI 3 / Swagger UI · Maven · Docker Compose。

---

## 1. 一键启动（Docker Compose，含 PostgreSQL）

```bash
docker compose up --build
```

启动后：

- 服务地址：http://localhost:8080
- Swagger UI（可直接试调全部接口）：http://localhost:8080/swagger-ui.html
- OpenAPI JSON：http://localhost:8080/v3/api-docs

首次启动 Flyway 自动建表并写入种子数据（5 个分馆、4 部书目、13 册馆藏、4 位读者、全部规则参数）。停止并清理：`docker compose down -v`。

## 2. 本地开发启动

前置：JDK 17、Maven 3.8+、PostgreSQL 14+。

```bash
# 1) 准备数据库
createdb library
# 2) 设置数据库连接（可选，默认值如下）
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/library
export SPRING_DATASOURCE_USER=library
export SPRING_DATASOURCE_PASSWORD=library
# 3) 运行（Flyway 自动迁移）
mvn spring-boot:run
# 4) 打包
mvn package
java -jar target/city-library.jar
```

测试使用 H2（PostgreSQL 兼容模式）+ 同一套 Flyway 迁移脚本，无需本地数据库：

```bash
mvn test
```

## 3. 测试账号（种子数据）

| 角色 | 账号 | 说明 |
| --- | --- | --- |
| 读者 | `R001` 张三 | 借书证号即接口中的 `readerCardNo` |
| 读者 | `R002` 李四 | 同上 |
| 读者 | `R003` 王五 | 同上 |
| 读者 | `R004` 赵六 | 预置一笔逾期借阅（条码 `C-0002`）+ 城北自助机 `NORTH-K01` 扫描失败日志，用于直接体验“已还未入库”申诉 |
| 馆员 / 物流员 | 请求体中的 `staff` / `staffName` 字段 | 演示环境不做登录鉴权，操作留痕记录操作人姓名 |

分馆代码：`CENTRAL` 中心馆、`NORTH` 城北、`SOUTH` 城南、`WEST` 城西、`EAST` 城东远端分馆。
书目 ID：1《三体》（有电子书）、2《红楼梦》、3《解忧杂货店》（有电子书）、4《平凡的世界》。

## 4. 快速验证（端到端 curl 剧本）

以下剧本覆盖题目主链路：**检索 → 预约锁条码 → 调拨出库 → 到馆通知 → 借出 → 归还 → 统计**。

```bash
# 读者在城西检索《三体》：本馆 0 册、城东可调拨 1 册、ETA 72h、有电子书替代
curl -s -G "http://localhost:8080/api/catalog/search" \
  --data-urlencode "keyword=三体" --data-urlencode "pickupLibraryCode=WEST" | jq

# R001 预约，取书点城西 → 系统锁定城东条码并生成调拨任务
curl -s -X POST http://localhost:8080/api/holds/bibs/1 -H 'Content-Type: application/json' \
  -d '{"readerCardNo":"R001","pickupLibraryCode":"WEST"}' | jq

# 物流员看待出库任务、出库（记录出库馆/目的馆/批次/包裹号/ETA）
TASK_ID=$(curl -s http://localhost:8080/api/transfers/tasks/pending | jq '.[0].id')
curl -s -X POST http://localhost:8080/api/transfers/pickups -H 'Content-Type: application/json' \
  -d "{\"taskIds\":[$TASK_ID],\"staffName\":\"物流员小赵\"}" | jq
PKG=$(curl -s "http://localhost:8080/api/transfers/tasks/$TASK_ID" | jq -r .packageNo)

# 到馆签收 → R001 收到 READY 取书通知，图书在城西预约架（取书时限 3 天）
curl -s -X POST http://localhost:8080/api/transfers/arrivals -H 'Content-Type: application/json' \
  -d "{\"packageNo\":\"$PKG\"}" | jq
curl -s http://localhost:8080/api/notifications/readers/R001 | jq

# R001 借出并查看应还日期/续借资格
BARCODE=$(curl -s "http://localhost:8080/api/holds/readers/R001" | jq -r '.[0].itemBarcode')
LOAN=$(curl -s -X POST http://localhost:8080/api/loans/checkouts -H 'Content-Type: application/json' \
  -d "{\"readerCardNo\":\"R001\",\"itemBarcode\":\"$BARCODE\",\"libraryCode\":\"WEST\"}")
LOAN_ID=$(echo "$LOAN" | jq .id)
curl -s "http://localhost:8080/api/loans/$LOAN_ID/renew-eligibility" | jq

# 还书核验（可传 damaged/missingPages/accessoryMissing，这里为完好归还）
curl -s -X POST http://localhost:8080/api/loans/returns -H 'Content-Type: application/json' \
  -d "{\"itemBarcode\":\"$BARCODE\",\"libraryCode\":\"WEST\"}" | jq

# 管理端：总览/分馆/热门/读者/批次/规则参数
curl -s "http://localhost:8080/api/admin/dashboard?days=30" | jq
curl -s "http://localhost:8080/api/admin/libraries" | jq
curl -s "http://localhost:8080/api/admin/bibs/popular" | jq
curl -s "http://localhost:8080/api/admin/readers?cardNo=R001" | jq
curl -s "http://localhost:8080/api/admin/batches" | jq
curl -s "http://localhost:8080/api/admin/policies" | jq
```

异常链路接口（均在 Swagger 中可试）：

- 取书超时释放：`POST /api/holds/jobs/expire`（定时任务每小时自动执行，也可手动触发）
- 物流丢包：`POST /api/transfers/tasks/{id}/lost` → 馆藏置 LOST、生成 `LOGISTICS` 赔偿单、读者免责保位并自动重新调拨
- 读者取消：`POST /api/holds/{id}/cancel`（未出库立即解锁；在途则到馆释放给下一位）
- 声称已还（已还未入库申诉）：`POST /api/claims/loans/{loanId}` 受理后罚款暂缓增长、信用暂扣；馆员经机日志/监控/书箱/入库记录核查后，走 `.../scan-fail-found`（扫描失败找到：撤罚入库修信用）、`.../keep-disputed`（未找到：保持争议，读者可继续举证）、`.../uphold-reader`（终判读者责任：按原到期日算罚款+赔书）、`.../library-missed-checkin`（确认馆方漏入库：豁免+修信用），详见第 4.1 节
- 污损/缺页/附件：还书接口置位 → 分别生成赔偿单，污损书退出预约流转
- 临时闭馆：`POST /api/libraries/{code}/closure` → 在该馆待取预约与临期借阅自动顺延 `closure.graceHours`
- 罚款人工调整：`POST /api/fines/{id}/adjustments`（REDUCE/WAIVE，必须填理由；原值/新值/操作人/规则依据全部审计留痕，`GET /api/fines/{id}/adjustments` 可查）

### 4.1 “已还未入库”申诉核查剧本（种子数据可直接跑）

读者收到逾期提醒但称已在自助机还书。服务围绕四类凭证组织证据链：**还书机日志 → 监控编号 → 书箱清点 → 馆员入库记录**。种子已为 `R004` 备好一笔逾期借阅（借阅单可从 `GET /api/loans/readers/R004/active` 取得，下例假定为 1）与城北自助机扫描失败日志。

```bash
# 1) 读者发起申诉（可带自助机编号与申报还书时刻）：罚款暂缓增长、借阅进入争议、信用暂扣
curl -s -X POST http://localhost:8080/api/claims/loans/1 -H 'Content-Type: application/json' \
  -d '{"kioskCode":"NORTH-K01","claimedReturnedAt":"2026-10-02T18:32:00Z","note":"机器响了但好像没扫上"}' | jq
# 账单中逾期罚款 growthHold=true（金额冻结，不减免）；信用已因逾期扣减且核查期间不恢复
curl -s http://localhost:8080/api/fines/readers/R004 | jq
curl -s http://localhost:8080/api/credit/readers/R004 | jq

# 2) 馆员核查：查自助机日志（种子已含 C-0002 的 SCAN_FAIL 记录）
curl -s -G http://localhost:8080/api/kiosk-logs --data-urlencode itemBarcode=C-0002 | jq

# 3a) 自助机扫描失败但书已找到：登记证据链 → 撤罚、补办入库修正库存、修复信用
curl -s -X POST http://localhost:8080/api/claims/1/scan-fail-found -H 'Content-Type: application/json' \
  -d '{"staff":"馆员老李","foundLibraryCode":"NORTH","kioskLogId":1,"cameraRef":"CAM-NORTH-1002","binCountNote":"开箱清点第2架位找到"}' | jq
# 工单详情含完整证据链
curl -s http://localhost:8080/api/claims/1 | jq
```

书未找到的另一条路径（保持争议 → 继续举证 → 终判）：

```bash
# 3b) 初步核查未找到：不判读者责任，保持争议，读者可继续举证（罚款继续暂缓、信用继续暂扣）
curl -s -X POST http://localhost:8080/api/claims/1/keep-disputed -H 'Content-Type: application/json' \
  -d '{"staff":"馆员老李","note":"当班书箱已清点未见此书"}' | jq
# 读者补充证据 → 工单自动回到 OPEN 重新核查
curl -s -X POST http://localhost:8080/api/claims/1/evidences -H 'Content-Type: application/json' \
  -d '{"content":"找到当晚照片，监控可查 19:00-19:30","kind":"READER","refNo":"PHOTO-1104"}' | jq
# 3c) 终判读者责任：图书置 LOST，逾期罚款解除暂缓并“按原到期日”补算，另生成赔书单，信用不恢复
curl -s -X POST http://localhost:8080/api/claims/1/uphold-reader -H 'Content-Type: application/json' \
  -d '{"staff":"馆员老李","reason":"跨班书箱多次清点均无此书，监控未见入箱"}' | jq
# 3d) 或馆方确认漏入库（如监控证实已投箱但馆员漏扫码）：豁免罚款、入库流转、同步修复信用
curl -s -X POST http://localhost:8080/api/claims/1/library-missed-checkin -H 'Content-Type: application/json' \
  -d '{"staff":"馆员老李","foundLibraryCode":"NORTH","reason":"监控证实已投箱，当班漏扫码入库"}' | jq
```

其余核查入口：馆员可用 `POST /api/claims/{id}/evidences/staff` 单独登记 `KIOSK_LOG/CAMERA/BIN_COUNT/CHECKIN_RECORD` 任一类证据，用 `POST /api/claims/{id}/kiosk-logs/{logId}` 直接挂接机日志；自助机可用 `POST /api/kiosk-logs` 上报 `SCAN_FAIL/BIN_JAM/OK` 日志。图书若在后续正常还书核验中被发现，申诉会自动结案为 FOUND 并撤罚修信用。

## 5. 领域模型与状态机

- `items` 馆藏条码：`AVAILABLE → LOCKED → IN_TRANSIT → ON_HOLD → CHECKED_OUT`，异常分支 `LOST / DAMAGED`。
- `holds` 预约：`QUEUED → READY → BORROWED`；异常 `CANCELLED / EXPIRED / LOST`。
- `transfer_tasks` 调拨：`CREATED → PICKED_UP → ARRIVED`；异常 `LOST / CANCELLED`；按 `transfer_batches` 物流批次聚合包裹。
- `loans` 借阅：`ACTIVE → RETURNED`；争议 `CLAIMED_RETURNED`；确认丢失 `LOST`。
- `claims` 已还未入库申诉：`OPEN` 核查中 → `DISPUTED` 未找到保持争议（读者可继续举证，举证后回到 `OPEN`）；结案 `FOUND`（自助机扫描失败但书已找到）/ `NOT_FOUND`（终判读者责任）/ `WAIVED`（确认馆方漏入库）。
- `fines` 罚款/赔偿：类型含逾期、污损、缺页、附件、赔书、物流丢包、人工；承担方 `READER / LIBRARY / LOGISTICS`；状态 `OPEN / PAID / WAIVED / REDUCED`，调整记录在 `fine_adjustments`；`growth_hold=true` 表示申诉核查期间暂缓日增长。
- `kiosk_logs` 自助还书机日志：`SCAN_FAIL` 扫描失败 / `BIN_JAM` 书箱卡滞 / `OK` 正常回收。
- `claim_evidences` 申诉证据链：`KIOSK_LOG` 机日志 / `CAMERA` 监控编号 / `BIN_COUNT` 书箱清点 / `CHECKIN_RECORD` 馆员入库记录 / `READER` 读者举证 / `REOPEN` 重新核查，来源分 `STAFF / READER`。
- `credit_records` 借阅信用流水：`OVERDUE / LOST` 扣减、`REPAIR` 修复；申诉核查期间不立即恢复。

一致性设计要点：

1. **单一再分配入口**：取消、超时、到馆失效、完好归还产生的在馆副本，统一经 `HoldService.reallocate(item, library)` 分配给队列首位（取书点在本馆的等待者优先，省一趟物流），无人排队则上架。
2. **统一加锁顺序**：所有同时操作“预约 ↔ 馆藏”的入口先锁该书目活跃预约行（`SELECT … FOR UPDATE`），再锁具体馆藏行，避免并发抢同一本与死锁。
3. **异常状态联动**：丢包/损坏/争议/闭馆在库存、预约、借阅、罚款之间同步变更，且同一事务提交。

## 6. 公平性与成本规则（可在管理端调整，见 `policies`）

| 规则 key | 默认值 | 含义 |
| --- | --- | --- |
| `loan.periodDays` | 30 | 标准借阅期限 |
| `loan.renewLimit` | 1 | 续借次数上限；且**该书有人排队时禁止续借**——热门书不被少数读者长期占用 |
| `hold.pickupDays` | 3 | 到馆取书时限，超时释放给下一位 |
| `hold.maxPerReader` | 10 | 单人同时有效预约上限 |
| `fine.daily` / `fine.cap` | 0.5 元/天 / 20 元 | 逾期罚款单价与单册封顶 |
| `fee.damage/missingPages/accessory/replacement/logisticsLoss` | 10/20/5/50/60 | 污损、缺页、缺附件、赔书、物流丢包赔付 |
| `transfer.protectionRatio` | 0.5 | **外馆保护**：调走 1 册后该馆留存可借数不得低于 ⌈比例×在流转馆藏数⌉，远端分馆库存不会被抽空（单册分馆默认保留） |
| `transfer.sameRegionHours/otherRegionHours` | 24/72 | 同/跨片区 ETA；选源馆时按 ETA、再按距离取最优，控制物流成本 |
| `closure.graceHours` | 24 | 临时闭馆时取书时限与应还日期自动顺延 |
| `credit.baseScore` | 100 | 借阅信用初始分 |
| `credit.overduePenalty` / `credit.lostPenalty` | 10 / 30 | 每笔逾期扣减 / 确认读者责任丢书扣减；申诉成立（馆方漏入库）时随结案修复 |

### 申诉期间的费用与信用规则

1. **罚款暂缓而非减免**：受理即在逾期罚款上置 `growth_hold`，每日批处理不再累加金额；终判读者责任时解除暂缓并**按原到期日**补算暂缓天数，确认馆方责任则撤销（WAIVE，留痕）。
2. **信用不立即恢复**：逾期首次产生罚款时即扣减信用，申诉核查期间维持扣减；仅当 `FOUND`（扫描失败找到）/`WAIVED`（馆方漏入库）时写入 `REPAIR` 流水恢复，读者责任（`NOT_FOUND`）不恢复并追加丢书扣减。
3. **争议可延续**：书未找到不直接判罚，置 `DISPUTED`；读者继续举证后工单回 `OPEN` 重新核查，避免单方推定。
4. **库存一致性**：确认馆方责任时按读者申报还书时刻（缺省取结案时刻）补办入库，图书经统一再分配入口交给排队者或上架。

每条罚款都携带 `ruleBasis`（规则依据文案），馆员处理异常与人工调整时可直接向读者解释；调减/豁免强制填写理由并保留原值、新值、操作人、时间戳。

## 7. 本次实际执行过的验证

- `mvn test`：**17 个测试全部通过**（H2 + 真实 Flyway 迁移，含 V3）：
  - `LibraryFlowTests`（14 个端到端业务流程）：检索视图与外馆保护、跨馆调拨全链路、FIFO 排队与超时释放、保护比例收紧、物流丢包保位重拨与物流赔偿、出库前/在途取消、逾期罚款累计封顶/逾期阻借/排队阻续、污损缺页附件赔偿与退出流转、临时闭馆双顺延、管理端统计一致性；“已还未入库”申诉 4 个场景——**①自助机扫描失败但书已找到：罚款冻结→撤销 WAIVE、库存修正上架、信用 90→100 修复且证据链四类齐全；②未找到保持 DISPUTED→读者继续举证回 OPEN→终判读者责任：冻结解除、逾期按原到期日补算（2.5→7.5 元）+赔书 50、信用 90→60 不恢复；③馆方确认漏入库：豁免+修信用；④图书在还书核验中出现时自动结案撤罚修信用**；另含人工调减审计留痕。
  - `WebApiTests`（3 个 HTTP 冒烟）：REST JSON、参数校验 400、OpenAPI 3 文档与 Swagger UI 可访问、自助机日志/信用/申诉端点可用。
- `mvn package`：构建出 `target/city-library.jar`（约 57 MB，可执行 fat jar）。
- 真实数据库冒烟：已用 **PostgreSQL 15**（本机临时实例 5439 端口）实际启动该 JAR，V1→V2→**V3** Flyway 迁移全部成功、种子数据（含 R004 逾期单与 `NORTH-K01` 的 SCAN_FAIL 日志）写入正常；通过 curl 实际走通两条申诉链路：
  - 场景 A：R004 发起申诉（逾期罚款 1.5 元 `growthHold=true`、信用 90）→ 挂接机日志 → `scan-fail-found`（FOUND / LIBRARY_MISSED_CHECKIN，罚款变 0 WAIVED、信用回 100、证据链含 KIOSK_LOG/CAMERA/BIN_COUNT/CHECKIN_RECORD、收到结案通知）；
  - 场景 B：新逾期单（14 天=7 元）受理后冻结 → `keep-disputed`（DISPUTED，仍在 `/api/claims/open`）→ 读者举证回 OPEN → `uphold-reader`（NOT_FOUND / READER_RESPONSIBLE，罚款解冻仍 7.0、赔书 50 生成、信用 60 且含 OVERDUE -10 / LOST -30 两条流水）。
- 未执行项：本环境无 Docker 守护进程，`docker compose up` 未在本机实际运行；Dockerfile 采用标准 Maven 多阶段构建 + eclipse-temurin:17-jre，且 compose 使用的 PostgreSQL 建表迁移脚本已在上述真实 PG 15 实例上验证通过。
