package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 自助机扫描失败但书已在书箱清点/架上找到：撤罚并修正库存。
 * binNo：书在哪个书箱找到（书箱清点结论）。
 */
public record ScanFailFoundRequest(
        @NotBlank String staff,
        @NotBlank String foundLibraryCode,
        String binNo,
        String note
) {}
