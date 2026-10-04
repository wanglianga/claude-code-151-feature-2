package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 申诉证据：读者补充举证（READER）或馆员核查记录（STAFF），按时间累积成证据链 */
@Entity
@Table(name = "claim_evidence")
@Getter
@Setter
public class ClaimEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id")
    private Claim claim;

    /** READER 读者补充 / STAFF 馆员核查 */
    @Column(nullable = false, length = 16)
    private String source;

    /** KIOSK / CAMERA / BIN / SHELF / RECEIPT / OTHER 等证据类别 */
    @Column(nullable = false, length = 24)
    private String kind;

    @Column(nullable = false, length = 1024)
    private String content;

    /** 馆员记录时的操作人 */
    private String staff;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
