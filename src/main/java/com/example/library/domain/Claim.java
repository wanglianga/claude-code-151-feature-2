package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 读者“已还未入库”申诉工单：收到逾期提醒但称已在自助机还书，馆员核查机日志/监控/书箱/入库记录 */
@Entity
@Table(name = "claims")
@Getter
@Setter
public class Claim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "loan_id")
    private Loan loan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reader_id")
    private Reader reader;

    /** OPEN 核查中 / DISPUTED 未找到保持争议 / FOUND 扫描失败已找到 / NOT_FOUND 读者责任 / WAIVED 馆方漏入库 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status;

    /** 读者申报还书使用的自助机编号 */
    @Column(name = "kiosk_code", length = 32)
    private String kioskCode;

    /** 读者申报的还书时刻 */
    @Column(name = "claimed_returned_at")
    private Instant claimedReturnedAt;

    /** 罚款暂缓起始时刻（受理即冻结日累计，不减免） */
    @Column(name = "held_at")
    private Instant heldAt;

    /** 终判责任方：READER_RESPONSIBLE / LIBRARY_MISSED_CHECKIN */
    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ClaimOutcome outcome;

    /** 馆方责任结案时信用记录是否已同步修复 */
    @Column(name = "credit_repaired")
    private Boolean creditRepaired;

    private String note;

    private String staff;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
