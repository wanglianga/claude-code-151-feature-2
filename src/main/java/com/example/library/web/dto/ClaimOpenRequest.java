package com.example.library.web.dto;

/** 读者提交“已还未入库”申诉：可附还书机编号与经过说明 */
public record ClaimOpenRequest(
        String note,
        String machineCode
) {}
