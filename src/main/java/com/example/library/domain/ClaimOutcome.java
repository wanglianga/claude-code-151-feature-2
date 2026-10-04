package com.example.library.domain;

/** 申诉终判责任方 */
public enum ClaimOutcome {
    /** 读者责任（书未找到，罚款按原到期日计算，信用不恢复） */
    READER_RESPONSIBLE,
    /** 馆方漏入库（罚款撤销/豁免，信用记录同步修复） */
    LIBRARY_MISSED_CHECKIN
}
