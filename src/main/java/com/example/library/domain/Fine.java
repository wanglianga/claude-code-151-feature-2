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

    /** 申诉核查期间暂缓日增长：金额冻结在受理时刻，不减免；结案后按责任方解冻/撤销 */
    @Column(name = "growth_hold", nullable = false)
    private boolean growthHold = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
