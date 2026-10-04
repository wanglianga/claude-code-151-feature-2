package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 读者借阅信用流水。
 * 逾期/确认丢书计负分（OVERDUE/LOST，points 为负）；
 * 申诉结案确认馆方漏入库时，写入 REPAIR 正分对同一借阅的扣分进行修复。
 * 申诉处理期间不写入任何信用事件——信用不立即恢复，也不因争议中逾期新增扣分。
 */
@Entity
@Table(name = "credit_events")
@Getter
@Setter
public class CreditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reader_id")
    private Reader reader;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @Column(name = "claim_id")
    private Long claimId;

    /** OVERDUE 逾期 / LOST 丢书 / REPAIR 馆方责任信用修复 */
    @Column(nullable = false, length = 16)
    private String type;

    /** 正分为修复/奖励，负分为扣减 */
    @Column(nullable = false)
    private int points;

    @Column(nullable = false, length = 512)
    private String reason;

    private String staff;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
