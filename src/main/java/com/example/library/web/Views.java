package com.example.library.web;

import com.example.library.domain.*;
import com.example.library.web.dto.*;

/** 实体 → 出参视图转换（必须在事务/打开的持久化上下文中调用以读取懒加载关联） */
public final class Views {
    private Views() {}

    public static HoldView hold(Hold h) {
        return new HoldView(h.getId(), h.getBib().getId(), h.getBib().getTitle(),
                h.getReader().getCardNo(),
                h.getPickupLibrary().getCode(), h.getPickupLibrary().getName(),
                h.getQueuePosition(), h.getStatus().name(),
                h.getItem() == null ? null : h.getItem().getBarcode(),
                h.getCreatedAt(), h.getReadyAt(), h.getExpiresAt(), h.getCancelledAt());
    }

    public static LoanView loan(Loan l) {
        return new LoanView(l.getId(), l.getReader().getCardNo(), l.getReader().getName(),
                l.getItem().getBarcode(), l.getItem().getBib().getTitle(),
                l.getCheckoutLibrary().getCode(),
                l.getCheckedOutAt(), l.getDueDate(), l.getRenewCount(), l.getStatus().name(),
                l.getReturnedAt(),
                l.getReturnedLibrary() == null ? null : l.getReturnedLibrary().getCode(),
                l.getConditionNote());
    }

    public static TaskView task(TransferTask t) {
        return new TaskView(t.getId(),
                t.getHold() == null ? null : t.getHold().getId(),
                t.getItem().getBarcode(), t.getItem().getBib().getTitle(),
                t.getFromLibrary().getCode(), t.getToLibrary().getCode(),
                t.getBatch() == null ? null : t.getBatch().getId(),
                t.getPackageNo(), t.getStatus().name(), t.isReleaseOnArrival(),
                t.getCreatedAt(), t.getPickedAt(), t.getEta(), t.getArrivedAt());
    }

    public static BatchView batch(TransferBatch b) {
        return new BatchView(b.getId(), b.getBatchNo(), b.getStaffName(), b.getStatus(),
                b.getCreatedAt(), b.getClosedAt());
    }

    public static FineView fine(Fine f) {
        return new FineView(f.getId(),
                f.getReader() == null ? null : f.getReader().getCardNo(),
                f.getLoan() == null ? null : f.getLoan().getId(),
                f.getTask() == null ? null : f.getTask().getId(),
                f.getType().name(), f.getChargedTo().name(), f.getAmount(), f.getStatus().name(),
                f.getReason(), f.getRuleBasis(), f.getCreatedAt(), f.getResolvedAt(),
                f.getFrozenClaimId() != null, f.getFrozenClaimId());
    }

    public static ClaimView claim(Claim c) {
        return claim(c, null);
    }

    /** @param frozenFine 该工单当前冻结的逾期罚款（可为 null） */
    public static ClaimView claim(Claim c, Fine frozenFine) {
        Loan l = c.getLoan();
        return new ClaimView(c.getId(), l.getId(), c.getReader().getCardNo(),
                l.getItem().getBarcode(), l.getItem().getBib().getTitle(),
                c.getStatus().name(), c.getNote(), c.getStaff(), c.getCreatedAt(), c.getResolvedAt(),
                c.getMachineCode(),
                c.getKioskCheck() == null ? null : c.getKioskCheck().name(),
                c.getKioskLog() == null ? null : c.getKioskLog().getId(),
                c.getKioskNote(),
                c.getCameraRef(),
                c.getCameraCheck() == null ? null : c.getCameraCheck().name(),
                c.getCameraNote(),
                c.getBinCheck() == null ? null : c.getBinCheck().name(),
                c.getBinCountAt(), c.getBinNote(),
                c.getShelfCheck() == null ? null : c.getShelfCheck().name(),
                c.getShelfRecordedAt(), c.getShelfNote(),
                c.getFrozenAt(), c.getLiability(), c.getClosedAt(),
                l.getStatus().name(), l.getDueDate(),
                frozenFine != null, frozenFine == null ? null : frozenFine.getAmount());
    }

    public static KioskLogView kioskLog(SelfCheckinLog l) {
        return new KioskLogView(l.getId(), l.getMachineCode(), l.getLibrary().getCode(),
                l.getItemBarcode(), l.getResult(), l.getOccurredAt(), l.getBinNo(),
                l.getCameraRef(), l.getNote());
    }

    public static EvidenceView evidence(ClaimEvidence e) {
        return new EvidenceView(e.getId(), e.getClaim().getId(), e.getSource(), e.getKind(),
                e.getContent(), e.getStaff(), e.getCreatedAt());
    }

    public static NotificationView notification(Notification n) {
        return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getMessage(),
                n.getCreatedAt(), n.getReadAt());
    }

    public static LibraryView library(Library l) {
        return new LibraryView(l.getId(), l.getCode(), l.getName(), l.getRegion(),
                l.isTemporaryClosed(), l.isActive());
    }
}
