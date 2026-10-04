package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/** 读者补充证据（书未找到、争议保持中可继续提交，提交后工单重新核查） */
public record ReaderEvidenceRequest(
        @NotBlank String content,
        /** 可指定证据类型，默认 READER */
        String kind,
        String refNo
) {}
