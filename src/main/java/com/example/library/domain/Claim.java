package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 读者“已还未入库”争议工单。
 * 核查四要素：还书机日志（kiosk）、监控编号（camera）、书箱清点（bin）、馆员入库记录（shelf）。
 * OPEN/DISPUTED 期间逾期罚款冻结、借阅信用不立即恢复；结案时按责任方撤罚修复或解冻计罚。
 */
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

    /** OPEN / DISPUTED / FOUND / READER_LIABLE / LIBRARY_LIABLE / WAIVED */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status;

    private String note;

    private String staff;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    // ---------------- 自助还书机核查 ----------------

    /** 读者申报使用的还书机编号（受理时可填） */
    @Column(name = "machine_code", length = 32)
    private String machineCode;

    /** PENDING / MATCH / NOT_FOUND / NA */
    @Enumerated(EnumType.STRING)
    @Column(name = "kiosk_check", nullable = false, length = 16)
    private ClaimCheck kioskCheck = ClaimCheck.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "kiosk_log_id")
    private SelfCheckinLog kioskLog;

    @Column(name = "kiosk_note")
    private String kioskNote;

    // ---------------- 监控核查 ----------------

    @Column(name = "camera_ref", length = 64)
    private String cameraRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "camera_check", nullable = false, length = 16)
    private ClaimCheck cameraCheck = ClaimCheck.PENDING;

    @Column(name = "camera_note")
    private String cameraNote;

    // ---------------- 书箱清点核查 ----------------

    @Enumerated(EnumType.STRING)
    @Column(name = "bin_check", nullable = false, length = 16)
    private ClaimCheck binCheck = ClaimCheck.PENDING;

    @Column(name = "bin_count_at")
    private Instant binCountAt;

    @Column(name = "bin_note")
    private String binNote;

    // ---------------- 馆员入库记录核查 ----------------

    @Enumerated(EnumType.STRING)
    @Column(name = "shelf_check", nullable = false, length = 16)
    private ClaimCheck shelfCheck = ClaimCheck.PENDING;

    @Column(name = "shelf_recorded_at")
    private Instant shelfRecordedAt;

    @Column(name = "shelf_note")
    private String shelfNote;

    // ---------------- 冻结与定责 ----------------

    /** 受理时刻：该借阅的逾期罚款在此刻冻结，争议期间不增长 */
    @Column(name = "frozen_at")
    private Instant frozenAt;

    /** 最终责任：READER 读者 / LIBRARY 馆方（漏入库/设备故障）；未结案为 null */
    @Column(length = 16)
    private String liability;

    @Column(name = "closed_at")
    private Instant closedAt;
}
