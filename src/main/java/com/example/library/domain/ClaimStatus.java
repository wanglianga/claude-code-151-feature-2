package com.example.library.domain;

public enum ClaimStatus {
    /** 已受理，馆方核查中；逾期罚款已冻结 */
    OPEN,
    /** 核查未找到书，争议保持；读者可继续提供证据 */
    DISPUTED,
    /** 自助机扫描失败但书已在书箱/架上找到：罚款撤销、库存修正、借阅结案 */
    FOUND,
    /** 最终确认读者责任：逾期按原到期日计或按丢书赔偿 */
    READER_LIABLE,
    /** 最终确认馆方漏入库：罚款撤销、借阅结案、信用记录同步修复 */
    LIBRARY_LIABLE,
    /** 馆方豁免（旧流程保留）：图书入库流转、罚款豁免 */
    WAIVED
}
