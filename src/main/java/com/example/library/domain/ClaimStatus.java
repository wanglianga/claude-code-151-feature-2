package com.example.library.domain;

public enum ClaimStatus {
    /** 已受理，等待馆员核查还书机日志/监控/书箱/入库记录 */
    OPEN,
    /** 初步核查未找到，争议保持，读者可继续提供证据 */
    DISPUTED,
    /** 自助机扫描失败但书已找到：撤销罚款、修正库存，借阅正常归还 */
    FOUND,
    /** 终判读者责任：图书未找到，按原到期日计逾期并生成赔书单 */
    NOT_FOUND,
    /** 馆方漏入库确认：豁免罚款、图书入库流转、信用记录修复 */
    WAIVED
}
