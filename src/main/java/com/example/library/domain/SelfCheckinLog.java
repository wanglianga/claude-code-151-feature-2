package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 自助还书机日志：每次投书（无论扫描成功或失败）都落一条记录，
 * 供“已还未入库”申诉核查还书机日志。
 */
@Entity
@Table(name = "self_checkin_logs")
@Getter
@Setter
public class SelfCheckinLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "machine_code", nullable = false, length = 32)
    private String machineCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id")
    private Library library;

    /** 扫描成功时可解析出的馆藏条码；扫描失败时可能为空 */
    @Column(name = "item_barcode", length = 32)
    private String itemBarcode;

    /** SCAN_SUCCESS / SCAN_FAILED */
    @Column(nullable = false, length = 16)
    private String result;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** 书落入的书箱编号 */
    @Column(name = "bin_no", length = 32)
    private String binNo;

    /** 关联监控点位/录像编号 */
    @Column(name = "camera_ref", length = 64)
    private String cameraRef;

    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
