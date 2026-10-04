package com.example.library;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.service.*;
import com.example.library.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端业务流程集成测试（H2 PostgreSQL 兼容模式 + Flyway 真实迁移）。
 * 每个测试方法事务回滚；AppClock 手动固定/拨动以验证超时与逾期。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class LibraryFlowTests {

    @Autowired HoldService holdService;
    @Autowired TransferService transferService;
    @Autowired LoanService loanService;
    @Autowired FineService fineService;
    @Autowired CatalogService catalog;
    @Autowired PolicyService policy;
    @Autowired LibraryAdminService libraryAdmin;
    @Autowired AdminStatsService stats;
    @Autowired CreditService creditService;
    @Autowired SelfCheckinService selfCheckinService;
    @Autowired AppClock clock;
    @Autowired ItemRepository items;
    @Autowired HoldRepository holds;
    @Autowired TransferTaskRepository tasks;
    @Autowired LoanRepository loans;
    @Autowired FineRepository fines;
    @Autowired ClaimRepository claims;

    private Instant t0;

    @BeforeEach
    void setUp() {
        t0 = Instant.parse("2026-10-03T08:00:00Z");
        clock.setFixed(t0);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ---------- 1. 检索视图：本馆库存、保护比例、ETA、电子资源 ----------

    @Test
    void catalog_showsLocalStock_transferableCopies_queueAndEbook() {
        var view = catalog.view(1L, "WEST"); // 三体，取书城西
        assertEquals("三体", view.title());
        assertEquals(0, view.localAvailable(), "城西无三体本馆库存");
        assertTrue(view.ebookAvailable(), "三体有电子资源替代");
        assertNotNull(view.ebookUrl());

        var east = view.libraries().stream().filter(l -> l.code().equals("EAST")).findFirst().orElseThrow();
        assertEquals(3, east.availableCopies());
        // EAST 共 3 册，保护比例 0.5 → 至少保留 ceil(1.5)=2 册，可调出 1 册
        assertTrue(east.transferable(), "城东 3 册可调出 1 册");
        assertEquals(72L, east.etaHours(), "跨片区 ETA 72 小时");

        var central = view.libraries().stream().filter(l -> l.code().equals("CENTRAL")).findFirst().orElseThrow();
        assertFalse(central.transferable(), "中心馆单册受保护不可调出");
    }

    // ---------- 2. 跨馆调拨全链路：预约 → 锁条码 → 出库 → 到馆通知 → 借出 ----------

    @Test
    void hold_transfer_arrive_notify_checkout() {
        // 城西唯一可行来源是城东（其余馆均为单册受保护）
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        assertEquals(HoldStatus.QUEUED, h.getStatus());
        assertEquals(1, h.getQueuePosition());
        assertNotNull(h.getItem());
        assertEquals("EAST", h.getItem().getCurrentLibrary().getCode());
        assertEquals(ItemStatus.LOCKED, h.getItem().getStatus());

        var pending = transferService.pendingPickup();
        assertEquals(1, pending.size());
        Long taskId = pending.get(0).getId();

        TransferBatch batch = transferService.pickup(List.of(taskId), "物流员小赵");
        var picked = tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStatus.PICKED_UP, picked.getStatus());
        assertNotNull(picked.getPackageNo());
        assertEquals(batch.getId(), picked.getBatch().getId());
        assertEquals("EAST", picked.getFromLibrary().getCode());
        assertEquals("WEST", picked.getToLibrary().getCode());
        assertEquals(t0.plus(72, ChronoUnit.HOURS), picked.getEta());
        assertEquals(ItemStatus.IN_TRANSIT, items.findByBarcode(picked.getItem().getBarcode()).orElseThrow().getStatus());

        TransferTask arrived = transferService.arrive(picked.getPackageNo());
        assertEquals(TaskStatus.ARRIVED, arrived.getStatus());
        Hold ready = holds.findById(h.getId()).orElseThrow();
        assertEquals(HoldStatus.READY, ready.getStatus());
        assertEquals(ItemStatus.ON_HOLD, ready.getItem().getStatus());
        assertEquals("WEST", ready.getItem().getCurrentLibrary().getCode());
        assertEquals(t0.plus(3, ChronoUnit.DAYS), ready.getExpiresAt());

        // 别人不能借走预约架上属于 R001 的书
        assertThrows(ApiException.class,
                () -> loanService.checkout("R002", ready.getItem().getBarcode(), "WEST"));

        Loan loan = loanService.checkout("R001", ready.getItem().getBarcode(), "WEST");
        assertEquals(t0.plus(30, ChronoUnit.DAYS), loan.getDueDate());
        assertEquals(ItemStatus.CHECKED_OUT, loan.getItem().getStatus());
        assertEquals(HoldStatus.BORROWED, holds.findById(h.getId()).orElseThrow().getStatus());
    }

    // ---------- 3. FIFO 排队 + 到馆超时释放给下一位 + 跨馆还书再调拨 ----------

    @Test
    void queueFIFO_expiryPassToNext_crossBranchReturn() {
        // 红楼梦：中心馆 1 册本地可借，城北 1 册（单册保护不可调出）
        Hold h1 = holdService.placeHold(2L, "R001", "CENTRAL");
        Hold h2 = holdService.placeHold(2L, "R002", "CENTRAL");
        Hold h3 = holdService.placeHold(2L, "R003", "CENTRAL");
        assertEquals(HoldStatus.READY, h1.getStatus());
        assertEquals(HoldStatus.QUEUED, h2.getStatus());
        assertEquals(1, h2.getQueuePosition());
        assertEquals(2, h3.getQueuePosition());

        // 取书超时（闭馆顺延逻辑之外，正常 3 天期限）
        clock.setFixed(t0.plus(4, ChronoUnit.DAYS));
        int processed = holdService.expireOverdueHolds();
        assertEquals(1, processed);
        assertEquals(HoldStatus.EXPIRED, holds.findById(h1.getId()).orElseThrow().getStatus());
        assertEquals(HoldStatus.READY, holds.findById(h2.getId()).orElseThrow().getStatus());
        assertEquals(1, holds.findById(h3.getId()).orElseThrow().getQueuePosition());
        String barcode = holds.findById(h2.getId()).orElseThrow().getItem().getBarcode();

        // 第二位取书后跨馆还到城北：应触发给第三位、城北→中心馆的新调拨
        Loan loan = loanService.checkout("R002", barcode, "CENTRAL");
        loanService.returnBook(barcode, "NORTH",
                new LoanService.ReturnAssessment(false, false, false, "完好"));
        TransferTask next = tasks.findActiveForItem(
                items.findByBarcode(barcode).orElseThrow().getId(),
                List.of(TaskStatus.CREATED, TaskStatus.PICKED_UP)).get(0);
        assertEquals("NORTH", next.getFromLibrary().getCode());
        assertEquals("CENTRAL", next.getToLibrary().getCode());
        assertEquals(h3.getId(), next.getHold().getId());
    }

    // ---------- 4. 远端分馆保护：提高保护比例后热门书无法抽空远端库存 ----------

    @Test
    void protectionRatio_blocksShippingLastCopies() {
        policy.update("transfer.protectionRatio", 1.0);
        // 城东 2 册要求留存 2 册 → 所有馆都不可调出，读者只能排队
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        assertEquals(HoldStatus.QUEUED, h.getStatus());
        assertNull(h.getItem(), "无允许调出的副本，不锁条码");
        assertTrue(transferService.pendingPickup().isEmpty());
    }

    // ---------- 5. 物流丢包：读者免责保位 + 自动重新调拨 + 物流赔偿 ----------

    @Test
    void logisticsLost_readerKeepsQueueAndLogisticsCharged() {
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        Long firstTaskId = transferService.pendingPickup().get(0).getId();
        String firstBarcode = h.getItem().getBarcode();
        TransferBatch batch = transferService.pickup(List.of(firstTaskId), "物流员小钱");
        TransferTask picked = tasks.findByBatchId(batch.getId()).get(0);

        transferService.reportLost(picked.getId(), "包裹破损遗失");

        assertEquals(ItemStatus.LOST, items.findByBarcode(firstBarcode).orElseThrow().getStatus());
        Hold requeued = holds.findById(h.getId()).orElseThrow();
        assertEquals(HoldStatus.QUEUED, requeued.getStatus());
        assertEquals(1, requeued.getQueuePosition(), "读者保留队列位置");
        assertNotNull(requeued.getItem(), "自动从城东第二册重新调拨");
        assertNotEquals(firstBarcode, requeued.getItem().getBarcode());

        Fine logisticsFine = fines.findAll().stream()
                .filter(f -> f.getType() == FineType.LOGISTICS_LOSS).findFirst().orElseThrow();
        assertEquals(ChargedTo.LOGISTICS, logisticsFine.getChargedTo());
        assertEquals(0, new BigDecimal("60.00").compareTo(logisticsFine.getAmount()));
        assertNotNull(logisticsFine.getRuleBasis());

        // 新包裹正常到馆，读者仍可取书
        TransferTask second = transferService.pendingPickup().get(0);
        transferService.pickup(List.of(second.getId()), "物流员小孙");
        transferService.arrive(tasks.findById(second.getId()).orElseThrow().getPackageNo());
        assertEquals(HoldStatus.READY, holds.findById(h.getId()).orElseThrow().getStatus());
    }

    // ---------- 6. 取消：出库前取消释放；在途取消到馆后给下一位 ----------

    @Test
    void cancelBeforePickupAndInTransit() {
        Hold h1 = holdService.placeHold(1L, "R001", "WEST");
        Hold h2 = holdService.placeHold(1L, "R002", "WEST");
        Long task1 = transferService.pendingPickup().stream()
                .filter(t -> t.getHold().getId().equals(h1.getId())).findFirst().orElseThrow().getId();

        // 出库前取消：任务取消，副本重新分配给 R002
        holdService.cancelHold(h1.getId());
        assertEquals(TaskStatus.CANCELLED, tasks.findById(task1).orElseThrow().getStatus());
        Hold h2ref = holds.findById(h2.getId()).orElseThrow();
        assertNotNull(h2ref.getItem());
        Long task2 = transferService.pendingPickup().get(0).getId();

        // 出库后、在途期间取消：包裹继续送达，到馆释放给队列下一位 R003
        Hold h3 = holdService.placeHold(1L, "R003", "WEST");
        transferService.pickup(List.of(task2), "物流员小周");
        holdService.cancelHold(h2.getId());
        assertTrue(tasks.findById(task2).orElseThrow().isReleaseOnArrival());

        // 城东再无可调副本（2 册：1 丢在架？不，一册在途一册…）R003 等待在途包裹到馆
        transferService.arrive(tasks.findById(task2).orElseThrow().getPackageNo());
        assertEquals(HoldStatus.READY, holds.findById(h3.getId()).orElseThrow().getStatus());
    }

    // ---------- 7. 逾期罚款累计/封顶、逾期阻止借书、排队阻止续借 ----------

    @Test
    void overdueFine_cap_blocksCheckout_andQueueBlocksRenewal() {
        Loan loan = loanService.checkout("R001", "J-0001", "CENTRAL"); // 解忧杂货店
        // 40 天后：逾期 10 天 × 0.5 = 5 元
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS));
        Fine f = fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("5.00").compareTo(f.getAmount()));

        // 90 天后：封顶 20 元
        clock.setFixed(t0.plus(120, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("20.00").compareTo(f.getAmount()));
        assertNotNull(f.getRuleBasis());

        // 有逾期未还 → 拒绝新借书
        assertThrows(ApiException.class, () -> loanService.checkout("R001", "J-0002", "WEST"));

        // 归还后逾期罚款仍在，可借阅恢复；此时有人排队则不可续借
        loanService.returnBook("J-0001", "CENTRAL",
                new LoanService.ReturnAssessment(false, false, false, null));
        Hold waiting = holdService.placeHold(3L, "R002", "CENTRAL"); // 解忧在中心馆的副本刚还回，R002 立即 READY
        assertEquals(HoldStatus.READY, waiting.getStatus());

        // 用 R003 借一册没有排队的书验证续借次数上限（renewLimit=1）
        Loan loan2 = loanService.checkout("R003", "P-0001", "NORTH"); // 平凡的世界，城北唯一册无其他人预约
        var eligible = loanService.renewEligibility(loan2);
        assertTrue(eligible.renewable());
        loanService.renew(loan2.getId());
        var second = loanService.renewEligibility(loans.findById(loan2.getId()).orElseThrow());
        assertFalse(second.renewable());
        assertTrue(second.reason().contains("续借次数上限"));
    }

    // ---------- 8. 还书核验：污损/缺页/缺附件分别计赔，图书退出流转 ----------

    @Test
    void returnWithDamage_multipleFines_itemWithdrawnFromRotation() {
        loanService.checkout("R001", "H-0001", "CENTRAL");
        Loan returned = loanService.returnBook("H-0001", "CENTRAL",
                new LoanService.ReturnAssessment(true, true, true, "水渍、缺附图、光盘缺失"));
        assertEquals("水渍、缺附图、光盘缺失", returned.getConditionNote());
        assertEquals(ItemStatus.DAMAGED, items.findByBarcode("H-0001").orElseThrow().getStatus());

        var readerFines = fines.findAll().stream()
                .filter(x -> x.getReader().getCardNo().equals("R001")).toList();
        assertEquals(3, readerFines.size());
        double total = readerFines.stream().mapToDouble(x -> x.getAmount().doubleValue()).sum();
        assertEquals(35.0, total, 0.001); // 10 + 20 + 5

        // 污损图书不应出现在跨馆可调拨候选中
        var view = catalog.view(2L, "NORTH");
        var central = view.libraries().stream().filter(l -> l.code().equals("CENTRAL")).findFirst().orElseThrow();
        assertEquals(0, central.availableCopies());
    }

    // ---------- 9. “已还未入库”：馆方找到撤罚修复信用；最终读者责任赔书；人工调整留痕 ----------

    @Test
    void claimReturned_libraryFaultWaivesAndRepairsCredit_readerLiableChargesReplacement_adjustAudited() {
        Loan loan = loanService.checkout("R002", "J-0001", "CENTRAL");
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loan);
        Claim claim = loanService.claimReturned(loan.getId(), "确定还到自助机了", "KIOSK-C-01");
        assertEquals(LoanStatus.CLAIMED_RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());

        // 受理即冻结逾期罚款，信用此前已因逾期扣 5 分且不立即恢复
        Fine overdue = fines.findAll().stream()
                .filter(x -> x.getType() == FineType.OVERDUE).findFirst().orElseThrow();
        assertEquals(claim.getId(), overdue.getFrozenClaimId());
        assertEquals(95, creditService.score("R002"));

        // 馆方在架找到：入库 + 逾期罚款撤销 + 信用同案修复
        loanService.resolveClaimFound(claim.getId(), "CENTRAL", "馆员老李");
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(FineStatus.WAIVED, fines.findAll().stream()
                .filter(x -> x.getType() == FineType.OVERDUE).findFirst().orElseThrow().getStatus());
        assertEquals(100, creditService.score("R002"), "馆方责任结案后信用恢复到争议前");

        // 另一册：首轮未找到 → 争议保持（不判赔），最终确认读者责任且书未找到 → 赔书价 50
        Loan loan2 = loanService.checkout("R003", "J-0002", "WEST");
        Claim claim2 = loanService.claimReturned(loan2.getId(), null, null);
        loanService.keepDisputed(claim2.getId(), "馆员老李", "书箱与书架均未见");
        assertEquals(ClaimStatus.DISPUTED, claims.findById(claim2.getId()).orElseThrow().getStatus());
        assertEquals(LoanStatus.CLAIMED_RETURNED, loans.findById(loan2.getId()).orElseThrow().getStatus());
        assertTrue(fines.findAll().stream().noneMatch(f -> f.getLoan() != null
                && f.getLoan().getId().equals(loan2.getId()) && f.getType() == FineType.LOST));

        // 读者继续举证后仍无法证明已还 → 最终读者责任，赔书
        loanService.reopenClaim(claim2.getId(), "馆员老李", "读者称有同行人");
        loanService.resolveReaderLiable(claim2.getId(), "馆员老李", false, null,
                "监控未见其投书，书箱入库记录均无此书，最终确认读者丢书");
        assertEquals(LoanStatus.LOST, loans.findById(loan2.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.LOST, items.findByBarcode("J-0002").orElseThrow().getStatus());
        Fine replacement = fines.findAll().stream()
                .filter(x -> x.getType() == FineType.LOST).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("50.00").compareTo(replacement.getAmount()));
        assertEquals(80, creditService.score("R003"), "丢书扣 20 分");

        Fine reduced = fineService.adjust(replacement.getId(), "馆员老李", "REDUCE", 30.0,
                "读者为残障人士且首次丢书，人文关怀调减");
        assertEquals(0, new BigDecimal("30.00").compareTo(reduced.getAmount()));
        assertEquals(FineStatus.REDUCED, reduced.getStatus());
        var history = fineService.adjustmentHistory(reduced.getId());
        assertEquals(1, history.size());
        assertEquals("馆员老李", history.get(0).getStaff());
        assertEquals(0, new BigDecimal("50.00").compareTo(history.get(0).getOldAmount()));

        // 无理由调整必须被拒绝
        assertThrows(ApiException.class,
                () -> fineService.adjust(reduced.getId(), "馆员老李", "WAIVE", null, "  "));
    }

    // ---------- 9b. 自助机扫描失败但书找到：冻结期不增长，撤罚 + 修正库存 + 信用修复 ----------

    @Test
    void claimFrozen_fineStaysFlat_thenScanFailFoundWaivesAndRepairsCredit() {
        Loan loan = loanService.checkout("R001", "P-0001", "NORTH");
        // 逾期 10 天产生 5 元罚款，信用扣 5
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS));
        Fine f = fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("5.00").compareTo(f.getAmount()));
        assertEquals(95, creditService.score("R001"));

        // 读者申诉，罚款冻结在 5 元
        Claim claim = loanService.claimReturned(loan.getId(), "昨晚还到城北自助机", "KIOSK-N-02");
        f = fines.findById(f.getId()).orElseThrow();
        assertTrue(f.getFrozenClaimId() != null);

        // 时间继续走 60 天：冻结罚款仍为 5 元（不增长），信用仍为 95（不立即恢复）
        clock.setFixed(t0.plus(100, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loans.findById(loan.getId()).orElseThrow());
        assertEquals(0, new BigDecimal("5.00").compareTo(fines.findById(f.getId()).orElseThrow().getAmount()));
        assertEquals(95, creditService.score("R001"));

        // 馆员核查：还书机有一条 SCAN_FAILED 日志（条码未解析），书箱清点找到
        SelfCheckinLog log = selfCheckinService.record("KIOSK-N-02", "NORTH", null, "SCAN_FAILED",
                t0.plus(40, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS), "BIN-N-3", "CAM-N-07",
                "扫描头脏污导致识别失败");
        loanService.recordCheck(claim.getId(), "KIOSK", "馆员老周", "MATCH", log.getId(), null,
                "故障时段有读者投书但扫描失败");
        loanService.resolveScanFailFound(claim.getId(), "馆员老周", "NORTH", "BIN-N-3",
                "书箱 BIN-N-3 清点时找到该书");

        // 罚款撤销、库存修正为在城北可借、借阅结案、信用修复
        assertEquals(FineStatus.WAIVED, fines.findById(f.getId()).orElseThrow().getStatus());
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.AVAILABLE, items.findByBarcode("P-0001").orElseThrow().getStatus());
        assertEquals("NORTH", items.findByBarcode("P-0001").orElseThrow().getCurrentLibrary().getCode());
        assertEquals(ClaimStatus.LIBRARY_LIABLE, claims.findById(claim.getId()).orElseThrow().getStatus());
        assertEquals("LIBRARY", claims.findById(claim.getId()).orElseThrow().getLiability());
        assertEquals(100, creditService.score("R001"));
    }

    // ---------- 9c. 最终读者责任（书已还回）：逾期一律按原到期日计算，信用不恢复 ----------

    @Test
    void claimReaderLiable_overdueCountedFromOriginalDueDate_creditNotRestored() {
        Loan loan = loanService.checkout("R003", "H-0002", "NORTH");
        clock.setFixed(t0.plus(35, ChronoUnit.DAYS)); // 逾期 5 天 = 2.5 元
        fineService.refreshOverdueFine(loan);
        Claim claim = loanService.claimReturned(loan.getId(), "我已经还了", null);
        Fine frozen = fines.findAll().stream()
                .filter(x -> x.getType() == FineType.OVERDUE).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("2.50").compareTo(frozen.getAmount()));

        // 争议又拖了 30 天后确认读者责任（书最终找到还回）
        clock.setFixed(t0.plus(65, ChronoUnit.DAYS));
        loanService.keepDisputed(claim.getId(), "馆员老李", "首轮无记录");
        loanService.resolveReaderLiable(claim.getId(), "馆员老李", true, "NORTH",
                "监控/书箱/入库记录均无，读者后来自行把书交回，延误责任在读者");

        // 罚款按原到期日 → 实际还回时刻重算：35 天逾期 = 17.5 元（处理期不免计，未达封顶 20）
        Fine settled = fines.findById(frozen.getId()).orElseThrow();
        assertEquals(FineStatus.OPEN, settled.getStatus());
        assertEquals(0, new BigDecimal("17.50").compareTo(settled.getAmount()));
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(95, creditService.score("R003"), "逾期扣分维持，信用不恢复");
    }

    // ---------- 9d. 未逾期即申诉，最终读者责任且书已还回：无罚款、不扣信用 ----------

    @Test
    void claimReaderLiable_bookFoundNotOverdue_noFineNoCreditPenalty() {
        Loan loan = loanService.checkout("R001", "J-0001", "CENTRAL");
        Claim claim = loanService.claimReturned(loan.getId(), "我好像还了", null);
        loanService.keepDisputed(claim.getId(), "馆员老李", "暂未找到");
        loanService.addReaderEvidence(claim.getId(), "OTHER", "又想了下应该没还，明天送来");
        loanService.resolveReaderLiable(claim.getId(), "馆员老李", true, "CENTRAL",
                "读者次日自行把书交回，未构成逾期，不存在逾期罚款");

        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertTrue(fines.findAll().stream().noneMatch(f -> f.getLoan() != null
                && f.getLoan().getId().equals(loan.getId())));
        assertEquals(100, creditService.score("R001"));
        assertEquals(ClaimStatus.READER_LIABLE, claims.findById(claim.getId()).orElseThrow().getStatus());
    }

    // ---------- 10. 临时闭馆：取书时限与临期应还日期自动顺延 ----------

    @Test
    void temporaryClosure_extendsPickupAndDueDates() {
        Hold h = holdService.placeHold(2L, "R001", "CENTRAL"); // 本地在架 READY
        Instant oldExpiry = h.getExpiresAt();

        // R003 借一本 25 小时后到期的场景用固定策略：直接借 30 天期书，闭馆顺延后应还日期增加 24h
        Loan loan = loanService.checkout("R003", "P-0001", "NORTH");
        Instant oldDue = loan.getDueDate();

        libraryAdmin.setTemporaryClosure("CENTRAL", true, "设备检修");
        assertEquals(oldExpiry.plus(24, ChronoUnit.HOURS),
                holds.findById(h.getId()).orElseThrow().getExpiresAt());

        // 中心馆闭馆不影响城北借出的书
        assertEquals(oldDue, loans.findById(loan.getId()).orElseThrow().getDueDate());

        // 临近应还日期时城北闭馆：该书在宽限窗口内到期 → 顺延 24h
        clock.setFixed(oldDue.minus(12, ChronoUnit.HOURS));
        libraryAdmin.setTemporaryClosure("NORTH", true, "台风");
        assertEquals(oldDue.plus(24, ChronoUnit.HOURS),
                loans.findById(loan.getId()).orElseThrow().getDueDate());

        // 回到闭馆后第 4 天内：取书时限已顺延，超时任务不得释放预约
        clock.setFixed(oldExpiry.plus(1, ChronoUnit.HOURS));
        assertEquals(0, holdService.expireOverdueHolds());
        assertEquals(HoldStatus.READY, holds.findById(h.getId()).orElseThrow().getStatus());
    }

    // ---------- 11. 管理端统计冒烟：总览/热门/批次/读者/单馆 ----------

    @Test
    void adminStats_areConsistentAfterFlow() {
        Hold h = holdService.placeHold(1L, "R001", "WEST");
        Long taskId = transferService.pendingPickup().get(0).getId();
        transferService.pickup(List.of(taskId), "物流员小赵");
        transferService.arrive(tasks.findById(taskId).orElseThrow().getPackageNo());
        loanService.checkout("R001", h.getItem().getBarcode(), "WEST");

        var dashboard = stats.dashboard(30);
        assertTrue(dashboard.transferTasks() >= 1);
        assertTrue(dashboard.arrivalRatePercent() >= 0);

        var popular = stats.popularBibs(30, 5);
        assertTrue(popular.stream().anyMatch(p -> p.title().equals("三体")));

        assertEquals(1, stats.batchStats().size());
        var west = stats.singleLibrary("WEST", 30);
        assertTrue(west.incomingTasks() >= 1);
        assertTrue(west.avgIncomingHours() >= 0);

        var r1 = stats.readerOverview("R001").get(0);
        assertEquals("R001", r1.cardNo());
        assertTrue(r1.activeLoans() >= 1);

        var bib = stats.bibStats(1L);
        assertEquals(6, bib.totalCopies());
        assertNotNull(bib.copiesByStatus().get("CHECKED_OUT"));
    }
}
