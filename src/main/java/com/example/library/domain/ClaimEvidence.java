package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 申诉证据链：读者举证与馆员核查（还书机日志/监控编号/书箱清点/入库记录） */
@Entity
@Table(name = "claim_evidences")
@Getter
@Setter
public class ClaimEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id")
    private Claim claim;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvidenceKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EvidenceSource source;

    @Column(nullable = false, length = 1024)
    private String content;

    /** 关联编号：监控片段号/书箱批次号/机日志 ID 等 */
    @Column(name = "ref_no", length = 64)
    private String refNo;

    /** 馆员核查登记时的操作人 */
    private String staff;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 读者提交时的证号或馆员姓名 */
    @Column(name = "created_by")
    private String createdBy;
}
