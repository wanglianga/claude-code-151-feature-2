package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.CreditEventRepository;
import com.example.library.repo.ReaderRepository;
import com.example.library.web.ApiException;
import com.example.library.web.dto.CreditEventView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 借阅信用服务。
 * 信用分 = 初始 100 + 全部信用流水之和；逾期/丢书计负，馆方漏入库定责时同案写入修复正分。
 * 申诉处理期间不写流水：信用不立即恢复，也不因争议中逾期追加扣分。
 */
@Service
public class CreditService {

    public static final int INITIAL_SCORE = 100;
    public static final int OVERDUE_POINTS = -5;
    public static final int LOST_POINTS = -20;

    private final CreditEventRepository events;
    private final ReaderRepository readers;
    private final AppClock clock;

    public CreditService(CreditEventRepository events, ReaderRepository readers, AppClock clock) {
        this.events = events;
        this.readers = readers;
        this.clock = clock;
    }

    @Transactional
    public CreditEvent record(Reader reader, Loan loan, Long claimId, String type, int points,
                              String reason, String staff) {
        CreditEvent e = new CreditEvent();
        e.setReader(reader);
        e.setLoan(loan);
        e.setClaimId(claimId);
        e.setType(type);
        e.setPoints(points);
        e.setReason(reason);
        e.setStaff(staff);
        e.setCreatedAt(clock.now());
        return events.save(e);
    }

    /** 逾期责任成立时扣减（同一借阅只记一次逾期扣分） */
    @Transactional
    public CreditEvent recordOverdueOnce(Loan loan, String reason, String staff) {
        boolean exists = events.findByReaderIdOrderByCreatedAtDesc(loan.getReader().getId()).stream()
                .anyMatch(e -> loan.getId().equals(e.getLoan() == null ? null : e.getLoan().getId())
                        && "OVERDUE".equals(e.getType()));
        if (exists) {
            return null;
        }
        return record(loan.getReader(), loan, null, "OVERDUE", OVERDUE_POINTS, reason, staff);
    }

    /** 确认丢书/读者责任赔书时扣减（同一借阅只记一次丢书扣分） */
    @Transactional
    public CreditEvent recordLostOnce(Loan loan, Long claimId, String reason, String staff) {
        boolean exists = events.findByReaderIdOrderByCreatedAtDesc(loan.getReader().getId()).stream()
                .anyMatch(e -> loan.getId().equals(e.getLoan() == null ? null : e.getLoan().getId())
                        && "LOST".equals(e.getType()));
        if (exists) {
            return null;
        }
        return record(loan.getReader(), loan, claimId, "LOST", LOST_POINTS, reason, staff);
    }

    /**
     * 馆方漏入库定责：对该借阅曾扣减的信用逐笔等额修复（正分流水，reason 关联申诉单号），
     * 使信用恢复到争议发生前的水平。
     */
    @Transactional
    public int repairForLoan(Loan loan, Long claimId, String staff) {
        Reader reader = loan.getReader();
        List<CreditEvent> prior = events.findByReaderIdOrderByCreatedAtDesc(reader.getId()).stream()
                .filter(e -> e.getLoan() != null && e.getLoan().getId().equals(loan.getId())
                        && e.getPoints() < 0 && !"REPAIR".equals(e.getType()))
                .toList();
        int repaired = 0;
        for (CreditEvent neg : prior) {
            record(reader, loan, claimId, "REPAIR", -neg.getPoints(),
                    "申诉 #" + claimId + " 确认馆方漏入库，修复此前「" + neg.getReason() + "」的信用扣减",
                    staff);
            repaired += -neg.getPoints();
        }
        return repaired;
    }

    @Transactional(readOnly = true)
    public int score(String cardNo) {
        Reader reader = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        return events.findByReaderIdOrderByCreatedAtDesc(reader.getId()).stream()
                .mapToInt(CreditEvent::getPoints).sum() + INITIAL_SCORE;
    }

    @Transactional(readOnly = true)
    public List<CreditEventView> viewsForReader(String cardNo) {
        Reader reader = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        return events.findByReaderIdOrderByCreatedAtDesc(reader.getId()).stream()
                .map(e -> new CreditEventView(e.getId(), e.getType(), e.getPoints(), e.getReason(),
                        e.getStaff(), e.getLoan() == null ? null : e.getLoan().getId(),
                        e.getClaimId(), e.getCreatedAt()))
                .toList();
    }
}
