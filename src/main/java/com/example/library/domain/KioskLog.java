package com.example.library.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 自助还书机运行日志（扫描失败、书箱卡滞等异常），申诉核查时用于佐证读者已还书 */
@Entity
@Table(name = "kiosk_logs")
@Getter
@Setter
public class KioskLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "library_code", nullable = false, length = 32)
    private String libraryCode;

    @Column(name = "kiosk_code", nullable = false, length = 32)
    private String kioskCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private KioskEventType eventType;

    @Column(name = "item_barcode", length = 32)
    private String itemBarcode;

    @Column(name = "reader_card_no", length = 32)
    private String readerCardNo;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private String detail;
}
