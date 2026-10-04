package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "fines")
@Getter
@Setter
public class Fine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reader_id")
    private Reader reader;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id")
    private TransferTask task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FineType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "charged_to", nullable = false, length = 16)
    private ChargedTo chargedTo;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FineStatus status;

    private String reason;

    /** 规则依据，馆员人工调整时展示 */
    @Column(name = "rule_basis")
    private String ruleBasis;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** 被哪条申诉冻结：非空时罚款挂起，不随逾期增长、不催收 */
    @Column(name = "frozen_claim_id")
    private Long frozenClaimId;

    @Column(name = "frozen_at")
    private Instant frozenAt;
}
