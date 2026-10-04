package com.example.library.domain;

/** 自助还书机事件类型 */
public enum KioskEventType {
    /** 扫描失败：图书入箱但未生成入库记录 */
    SCAN_FAIL,
    /** 书箱卡滞 */
    BIN_JAM,
    /** 正常回收 */
    OK
}
