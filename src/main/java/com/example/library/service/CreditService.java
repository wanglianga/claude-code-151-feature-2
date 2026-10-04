package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.CreditRecordRepository;
import com.example.library.web.dto.CreditRecordView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 借阅信用：逾期/丢书产生扣减；“已还未入库”申诉核查期间信用不立即恢复，
 * 仅当馆方确认漏入库（或自助机扫描失败但书已找到）时随结案一并修复。
 */
@Service
public class CreditService {

    private final CreditRecordRepository records;
    private final PolicyService policy;
    private final AppClock clock;

    public CreditService(CreditRecordRepository records, PolicyService policy, AppClock clock) {
        this.records = records;
        this.policy = policy;
        this.clock = clock;
    }

    /** 逾期首次产生时扣减信用（同一笔借阅只扣一次），在逾期罚款首次生成时调用 */
    @Transactional
    public void recordOverduePenaltyIfNeeded(Loan loan) {
        if (records.existsByReaderIdAndLoanIdAndType(
                loan.getReader().getId(), loan.getId(), CreditType.OVERDUE)) {
            return;
        }
        save(loan.getReader(), CreditType.OVERDUE, -policy.creditOverduePenalty(), loan, null,
                "《" + loan.getItem().getBib().getTitle() + "》条码 " + loan.getItem().getBarcode()
                        + " 逾期未还（应还 " + loan.getDueDate() + "），信用扣减 "
                        + policy.creditOverduePenalty() + " 分；如已在自助机还书可发起申诉，核查期间暂不恢复");
    }

    /** 终判读者责任且图书丢失：追加丢书扣减 */
    @Transactional
    public void recordLostPenalty(Loan loan, Claim claim) {
        if (records.existsByReaderIdAndLoanIdAndType(
                loan.getReader().getId(), loan.getId(), CreditType.LOST)) {
            return;
        }
        save(loan.getReader(), CreditType.LOST, -policy.creditLostPenalty(), loan, claim,
                "《" + loan.getItem().getBib().getTitle() + "》条码 " + loan.getItem().getBarcode()
                        + " 已还申诉终判读者责任、图书未找到，按丢书扣减信用 "
                        + policy.creditLostPenalty() + " 分");
    }

    /**
     * 馆方责任结案（漏入库 / 自助机扫描失败但书已找到）：
     * 撤销该笔借阅此前产生的逾期/丢书信用扣减，并记录修复流水。
     */
    @Transactional
    public int repairForClaim(Claim claim, String reason) {
        Loan loan = claim.getLoan();
        List<CreditRecord> negatives = records.findByReaderIdOrderByCreatedAtDesc(loan.getReader().getId())
                .stream()
                .filter(r -> r.getLoan() != null && r.getLoan().getId().equals(loan.getId())
                        && (r.getType() == CreditType.OVERDUE || r.getType() == CreditType.LOST))
                .toList();
        int restore = negatives.stream().mapToInt(CreditRecord::getChange).sum(); // 负值之和
        if (restore < 0) {
            save(loan.getReader(), CreditType.REPAIR, -restore, loan, claim, reason);
        }
        claim.setCreditRepaired(true);
        return -restore;
    }

    private void save(Reader reader, CreditType type, int change, Loan loan, Claim claim, String reason) {
        CreditRecord r = new CreditRecord();
        r.setReader(reader);
        r.setLoan(loan);
        r.setClaim(claim);
        r.setType(type);
        r.setChange(change);
        r.setReason(reason);
        r.setCreatedAt(clock.now());
        records.save(r);
    }

    @Transactional(readOnly = true)
    public int score(Long readerId) {
        int score = policy.creditBaseScore();
        for (CreditRecord r : records.findByReaderIdOrderByCreatedAtDesc(readerId)) {
            score += r.getChange();
        }
        return score;
    }

    @Transactional(readOnly = true)
    public List<CreditRecord> forReader(Long readerId) {
        return records.findByReaderIdOrderByCreatedAtDesc(readerId);
    }

    @Transactional(readOnly = true)
    public List<CreditRecordView> viewsForReader(Long readerId) {
        return forReader(readerId).stream().map(CreditRecordView::from).toList();
    }
}
