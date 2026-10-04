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

首次启动 Flyway 自动建表并写入种子数据（5 个分馆、4 部书目、13 册馆藏、3 位读者、全部规则参数）。停止并清理：`docker compose down -v`。

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
- 声称已还/已还未入库申诉（见第 8 节完整流程）：
  - 读者申报：`POST /api/claims/loans/{loanId}`（可附还书机编号 `machineCode`）→ **受理即冻结逾期罚款**（不撤销、不增长、不催收），借阅进 `CLAIMED_RETURNED`，**借阅信用不立即恢复**
  - 读者继续举证：`POST /api/claims/{id}/evidence`；证据链查询：`GET /api/claims/{id}/evidence`
  - 馆员四类核查：`POST /api/claims/{id}/checks/{KIOSK|CAMERA|BIN|SHELF}`（还书机日志 / 监控编号 / 书箱清点 / 馆员入库记录，结论 `MATCH/NOT_FOUND/NA`）；还书机日志由 `POST /api/kiosk/logs` 上报（`SCAN_SUCCESS`/`SCAN_FAILED` 均留痕）
  - 自助机扫描失败但书找到：`POST /api/claims/{id}/scan-fail-found` → **罚款撤销、库存修正、借阅结案、信用修复**
  - 首轮未找到：`POST /api/claims/{id}/not-found` → **不判赔，争议保持 `DISPUTED`**；读者补证据后馆员 `POST /api/claims/{id}/reopen` 重开
  - 最终定责：`POST /api/claims/{id}/reader-liable`（书已还回：逾期**按原到期日**计、信用不恢复；书未找到：赔书价 + 丢书信用扣分）；`POST /api/claims/{id}/library-liable`（馆方漏入库：撤罚、入库流转、**信用记录同案修复**）
  - 借阅信用：`GET /api/credit/readers/{cardNo}`（初始 100）、`GET /api/credit/readers/{cardNo}/events`（流水）；日终批处理可手动触发 `POST /api/loans/jobs/daily-overdue`
- 污损/缺页/附件：还书接口置位 → 分别生成赔偿单，污损书退出预约流转
- 临时闭馆：`POST /api/libraries/{code}/closure` → 在该馆待取预约与临期借阅自动顺延 `closure.graceHours`
- 罚款人工调整：`POST /api/fines/{id}/adjustments`（REDUCE/WAIVE，必须填理由；原值/新值/操作人/规则依据全部审计留痕，`GET /api/fines/{id}/adjustments` 可查）

## 5. 领域模型与状态机

- `items` 馆藏条码：`AVAILABLE → LOCKED → IN_TRANSIT → ON_HOLD → CHECKED_OUT`，异常分支 `LOST / DAMAGED`。
- `holds` 预约：`QUEUED → READY → BORROWED`；异常 `CANCELLED / EXPIRED / LOST`。
- `transfer_tasks` 调拨：`CREATED → PICKED_UP → ARRIVED`；异常 `LOST / CANCELLED`；按 `transfer_batches` 物流批次聚合包裹。
- `loans` 借阅：`ACTIVE → RETURNED`；争议 `CLAIMED_RETURNED`；确认丢失 `LOST`。
- `claims` 已还未入库申诉：`OPEN 核查中 → DISPUTED 争议保持（可 reopen 回 OPEN）→ FOUND 扫描失败找到 / LIBRARY_LIABLE 馆方漏入库 / READER_LIABLE 读者责任 / WAIVED 馆方豁免`。工单上分别留痕四项核查结论 `kiosk/camera/bin/shelf`（PENDING/MATCH/NOT_FOUND/NA）与证据链 `claim_evidence`。
- `fines` 罚款/赔偿：类型含逾期、污损、缺页、附件、赔书、物流丢包、人工；承担方 `READER / LIBRARY / LOGISTICS`；状态 `OPEN / PAID / WAIVED / REDUCED`，调整记录在 `fine_adjustments`；申诉受理后逾期单置 `frozen_claim_id` **冻结挂起**。
- `credit_events` 借阅信用流水：逾期 -5、丢书 -20、馆方责任 `REPAIR` 同案正分修复；信用分 = 100 + 流水之和。

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

每条罚款都携带 `ruleBasis`（规则依据文案），馆员处理异常与人工调整时可直接向读者解释；调减/豁免强制填写理由并保留原值、新值、操作人、时间戳。

## 7. “已还未入库”申诉处理规则

读者收到逾期提醒，但称已在自助还书机还书、系统却未入库。馆员按四类证据核查：**还书机日志、监控编号、书箱清点、馆员入库记录**。

1. **受理即冻结**：读者提交申诉后，借阅进入争议状态；该借阅已产生的逾期罚款**立即冻结**在受理时刻金额——不撤销、不增长、不催收（`fines.frozen_claim_id/frozen_at`）；尚无逾期单则不新建。日终批处理（每日 02:00，也可 `POST /api/loans/jobs/daily-overdue` 手动触发）跳过争议借阅。
2. **信用不立即恢复**：受理前因逾期扣的信用分（-5）保持，争议期间也不新增扣分；只有最终确认馆方责任才修复。
3. **四类核查分别留痕**：馆员可多次录入 KIOSK/CAMERA/BIN/SHELF 结论（`MATCH/NOT_FOUND/NA`），还书机核查可关联具体日志；自助机投书即记录，`SCAN_FAILED`（扫描头故障、条码未解析）也保留书箱号与监控编号。所有结论与读者举证进入 `claim_evidence` 证据链。
4. **扫描失败但书找到**：书在书箱清点/架上找到，确认系设备/入库流程问题 → 罚款撤销、馆藏状态与所在馆修正（重新参与预约流转）、借阅结案、**信用同案修复**。
5. **书未找到不立即判赔**：首轮核查无果 → 工单置 `DISPUTED` 争议保持，罚款继续冻结；读者可继续提供证据（凭条、同行人、具体时间等），馆员据新证据 `reopen` 回 `OPEN` 再次核查。
6. **最终定责**：
   - **读者责任**：书最终还回——逾期罚款一律**按原到期日 → 实际还回时刻**重新核算（申诉处理期不免计），信用扣分维持不恢复；书确认丢失——按 `fee.replacement` 赔书并计丢书信用扣分（-20）。
   - **馆方漏入库/设备故障**：读者侧未结罚款全部撤销，图书入库正常流转，该借阅此前的信用扣减逐笔等额写 `REPAIR` 正分修复，信用恢复到争议前水平。

## 8. 本次实际执行过的验证

- `mvn test`：**17 个测试全部通过**（H2 + 真实 Flyway 迁移 V1–V3）：
  - `LibraryFlowTests`（14 个端到端业务流程）：检索视图与外馆保护、跨馆调拨全链路、FIFO 排队与超时释放、保护比例收紧、物流丢包保位重拨与物流赔偿、出库前/在途取消、逾期罚款累计封顶/逾期阻借/排队阻续、污损缺页附件赔偿与退出流转、已还未入库馆方找到撤罚修复信用 / 最终读者责任赔书与人工调整审计、**罚款冻结期间金额不增且信用不恢复 → 自助机扫描失败找到撤罚修库存修信用**、**最终读者责任（书已还回）逾期按原到期日计、信用不恢复**、**未逾期判读者责任不产生罚款与信用扣分**、临时闭馆双顺延、管理端统计一致性。
  - `WebApiTests`（3 个 HTTP 冒烟）：REST JSON、参数校验 400、OpenAPI 3 文档与 Swagger UI 可访问；**已还未入库申诉完整 HTTP 链路**（自助机 SCAN_FAILED 上报 → 申诉受理冻结 → 读者举证/馆员核查 → 扫描失败找到馆方定责 → 信用 100）。
- `mvn package`：构建出 `target/city-library.jar`（约 57 MB，可执行 fat jar）。
- 真实数据库冒烟：已在本机用 **PostgreSQL 15** 实际启动该 JAR（Flyway V1–V3 建表播种成功），除原有跨馆调拨全链路外，本次走通申诉主场景：**借出 → 制造逾期 → 日终批处理生成逾期提醒/6 元罚款/信用 95 → 申诉受理后罚款冻结为 6 元（把逾期改到 72 天前再跑批，金额仍 6、信用仍 95）→ KIOSK/CAMERA/BIN 核查 MATCH → 馆方漏入库定责 → 罚款 WAIVED 0 元、库存修正为 AVAILABLE、信用修复回 100（REPAIR +5 流水）、通知送达**；并验证 `DISPUTED 争议保持 → 读者补证据 → reopen → 读者责任定责`、争议态直接核查返回 409 等分支；Swagger UI / OpenAPI JSON 含全部新接口均 200。
- 未执行项：本环境无 Docker 守护进程，`docker compose up` 未在本机实际运行；Dockerfile 采用标准 Maven 多阶段构建 + eclipse-temurin:17-jre，且 compose 使用的 PostgreSQL 建表迁移脚本（含 V3）已在上述真实 PG 15 实例上验证通过。
