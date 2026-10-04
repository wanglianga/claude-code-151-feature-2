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
    @Autowired ClaimService claimService;
    @Autowired KioskService kioskService;
    @Autowired CreditService creditService;
    @Autowired CatalogService catalog;
    @Autowired PolicyService policy;
    @Autowired LibraryAdminService libraryAdmin;
    @Autowired AdminStatsService stats;
    @Autowired AppClock clock;
    @Autowired ItemRepository items;
    @Autowired HoldRepository holds;
    @Autowired TransferTaskRepository tasks;
    @Autowired LoanRepository loans;
    @Autowired FineRepository fines;
    @Autowired ClaimRepository claims;
    @Autowired ClaimEvidenceRepository claimEvidences;
    @Autowired ReaderRepository readers;

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

    // ---------- 9. “已还未入库”申诉：扫描失败找到撤罚修信用 / 未找到保持争议 / 终判按原到期日 ----------

    @Test
    void claim_scanFailFound_waivesFineFixesInventoryRepairsCredit() {
        Loan loan = loanService.checkout("R002", "J-0001", "CENTRAL");
        Long readerId = loan.getReader().getId();
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS)); // 逾期 10 天 → 5 元
        Fine overdue = fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("5.00").compareTo(overdue.getAmount()));
        assertEquals(90, creditService.score(readerId), "逾期首次产生信用扣减 10 分");

        // 读者申诉：罚款冻结、借阅进入争议、信用不立即恢复
        Claim claim = claimService.openClaim(loan.getId(), "NORTH-K01",
                t0.plus(31, ChronoUnit.DAYS), "当晚在城北自助机还的，机器响了一声");
        assertEquals(ClaimStatus.OPEN, claims.findById(claim.getId()).orElseThrow().getStatus());
        assertEquals(LoanStatus.CLAIMED_RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertTrue(fines.findById(overdue.getId()).orElseThrow().isGrowthHold());
        assertEquals(90, creditService.score(readerId), "核查期间信用不恢复");

        // 时间继续走，但冻结期间罚款不再增长
        clock.setFixed(t0.plus(60, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loans.findById(loan.getId()).orElseThrow());
        assertEquals(0, new BigDecimal("5.00").compareTo(
                fines.findById(overdue.getId()).orElseThrow().getAmount()));

        // 自助机上报扫描失败日志，馆员核查后确认书已在书箱找到
        KioskLog log = kioskService.ingest("NORTH", "NORTH-K01", "SCAN_FAIL",
                "J-0001", "R002", t0.plus(31, ChronoUnit.DAYS), "读头识别失败，图书已落入书箱");
        claimService.resolveScanFailFound(claim.getId(), "CENTRAL", "馆员老李",
                log.getId(), "CAM-NORTH-1002", "10 月 3 日开箱清点第 2 架位找到");

        Claim resolved = claims.findById(claim.getId()).orElseThrow();
        assertEquals(ClaimStatus.FOUND, resolved.getStatus());
        assertEquals(ClaimOutcome.LIBRARY_MISSED_CHECKIN, resolved.getOutcome());
        assertTrue(resolved.getCreditRepaired());
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.AVAILABLE, items.findByBarcode("J-0001").orElseThrow().getStatus(),
                "无人排队则修正库存后在找到馆上架");
        Fine waived = fines.findById(overdue.getId()).orElseThrow();
        assertEquals(FineStatus.WAIVED, waived.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(waived.getAmount()));
        assertFalse(waived.isGrowthHold());
        assertEquals(100, creditService.score(readerId), "馆方责任：信用记录同步修复");

        // 证据链完整：机日志 / 监控编号 / 书箱清点 / 入库记录
        var kinds = claimEvidences.findByClaimIdOrderByCreatedAt(claim.getId()).stream()
                .map(e -> e.getKind()).toList();
        assertTrue(kinds.contains(EvidenceKind.KIOSK_LOG));
        assertTrue(kinds.contains(EvidenceKind.CAMERA));
        assertTrue(kinds.contains(EvidenceKind.BIN_COUNT));
        assertTrue(kinds.contains(EvidenceKind.CHECKIN_RECORD));
    }

    @Test
    void claim_keepDisputedReaderReopens_upholdChargesFromOriginalDueDate() {
        Loan loan = loanService.checkout("R003", "J-0002", "WEST");
        Long readerId = loan.getReader().getId();
        clock.setFixed(t0.plus(35, ChronoUnit.DAYS)); // 逾期 5 天 → 2.5 元
        Fine overdue = fineService.refreshOverdueFine(loan);
        assertEquals(0, new BigDecimal("2.50").compareTo(overdue.getAmount()));

        Claim claim = claimService.openClaim(loan.getId(), "WEST-K02", null, "确定还了");
        // 暂未找到：保持争议，罚款继续暂缓、信用继续暂扣
        claimService.keepDisputed(claim.getId(), "馆员老李", "当班书箱已清点，未见此书");
        assertEquals(ClaimStatus.DISPUTED, claims.findById(claim.getId()).orElseThrow().getStatus());
        assertTrue(fines.findById(overdue.getId()).orElseThrow().isGrowthHold());
        assertEquals(90, creditService.score(readerId));

        // 读者继续提供证据 → 工单回到 OPEN 重新核查
        claimService.addReaderEvidence(claim.getId(), "READER",
                "找到当晚照片，监控可查 19:00-19:30 时段", "PHOTO-2026-1104");
        assertEquals(ClaimStatus.OPEN, claims.findById(claim.getId()).orElseThrow().getStatus());

        // 终判读者责任：冻结解除，按原到期日补算（此时逾期 15 天 → 7.5 元）
        clock.setFixed(t0.plus(45, ChronoUnit.DAYS));
        claimService.upholdReaderResponsible(claim.getId(), "馆员老李",
                "监控仅见投递动作未见书入箱，跨班书箱多次清点均无此书");
        Claim done = claims.findById(claim.getId()).orElseThrow();
        assertEquals(ClaimStatus.NOT_FOUND, done.getStatus());
        assertEquals(ClaimOutcome.READER_RESPONSIBLE, done.getOutcome());
        assertNull(done.getCreditRepaired(), "读者责任不修复信用标记");
        assertEquals(LoanStatus.LOST, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(ItemStatus.LOST, items.findByBarcode("J-0002").orElseThrow().getStatus());

        Fine settled = fines.findById(overdue.getId()).orElseThrow();
        assertFalse(settled.isGrowthHold());
        assertEquals(0, new BigDecimal("7.50").compareTo(settled.getAmount()),
                "按原到期日计算：暂缓的 10 天照常计入（15×0.5）");
        Fine replacement = fines.findByLoanId(loan.getId()).stream()
                .filter(f -> f.getType() == FineType.LOST).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("50.00").compareTo(replacement.getAmount()));
        assertEquals(60, creditService.score(readerId), "逾期 -10、丢书 -30，信用不恢复");

        // 人工调减仍须审计留痕
        Fine reduced = fineService.adjust(replacement.getId(), "馆员老李", "REDUCE", 30.0,
                "读者首次丢书，人文关怀调减");
        assertEquals(FineStatus.REDUCED, reduced.getStatus());
        assertEquals(1, fineService.adjustmentHistory(replacement.getId()).size());
    }

    @Test
    void claim_libraryMissedCheckin_waivesFineAndRepairsCredit() {
        Loan loan = loanService.checkout("R001", "P-0001", "NORTH");
        Long readerId = loan.getReader().getId();
        clock.setFixed(t0.plus(40, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loan);
        Claim claim = claimService.openClaim(loan.getId(), null, null, null);

        claimService.confirmLibraryMissedCheckin(claim.getId(), "NORTH", "馆员老李",
                "监控证实读者已投入还书箱，系当班馆员漏扫码入库");
        Claim done = claims.findById(claim.getId()).orElseThrow();
        assertEquals(ClaimStatus.WAIVED, done.getStatus());
        assertEquals(ClaimOutcome.LIBRARY_MISSED_CHECKIN, done.getOutcome());
        assertTrue(done.getCreditRepaired());
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(100, creditService.score(readerId));
        assertTrue(fines.findByLoanId(loan.getId()).stream()
                .allMatch(f -> f.getStatus() == FineStatus.WAIVED));
    }

    @Test
    void claim_physicalReturnAutoCloses_fineWaivedAndCreditRepaired() {
        Loan loan = loanService.checkout("R002", "H-0002", "NORTH");
        Long readerId = loan.getReader().getId();
        clock.setFixed(t0.plus(34, ChronoUnit.DAYS));
        fineService.refreshOverdueFine(loan);
        Claim claim = claimService.openClaim(loan.getId(), "NORTH-K01", null, "还到城北了");

        // 图书在另一次还书核验中被发现（漏入库的书重新出现）
        loanService.returnBook("H-0002", "CENTRAL",
                new LoanService.ReturnAssessment(false, false, false, "书箱深处找出"));
        assertEquals(ClaimStatus.FOUND, claims.findById(claim.getId()).orElseThrow().getStatus());
        assertEquals(LoanStatus.RETURNED, loans.findById(loan.getId()).orElseThrow().getStatus());
        assertEquals(100, creditService.score(readerId));
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
