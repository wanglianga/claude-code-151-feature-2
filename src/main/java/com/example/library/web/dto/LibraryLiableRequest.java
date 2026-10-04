package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 最终确认馆方漏入库/设备故障：撤罚、图书入库流转、信用记录同案修复 */
public record LibraryLiableRequest(
        @NotBlank String staff,
        @NotBlank String foundLibraryCode,
        @NotBlank String reason
) {}
