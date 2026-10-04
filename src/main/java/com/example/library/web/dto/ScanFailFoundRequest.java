package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 自助机扫描失败但书已找到：馆员补办入库的结案请求 */
public record ScanFailFoundRequest(
        @NotBlank String staff,
        @NotBlank String foundLibraryCode,
        /** 佐证用还书机日志 ID（可空） */
        Long kioskLogId,
        /** 监控片段编号（可空） */
        String cameraRef,
        /** 书箱清点备注（可空） */
        String binCountNote
) {}
