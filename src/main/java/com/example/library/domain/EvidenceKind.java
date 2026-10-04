package com.example.library.domain;

/** 申诉证据类型：读者举证与馆员核查的四类凭证 */
public enum EvidenceKind {
    /** 还书机日志（扫描失败/书箱卡滞等） */
    KIOSK_LOG,
    /** 监控编号 */
    CAMERA,
    /** 书箱清点结果 */
    BIN_COUNT,
    /** 馆员入库记录 */
    CHECKIN_RECORD,
    /** 读者补充证据（照片/时间说明等） */
    READER,
    /** 争议重新核查说明 */
    REOPEN
}
