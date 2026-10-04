package com.example.library.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 最终确认读者责任。
 * bookFound=true：书事后找到并归还，借阅结案，逾期罚款一律按原到期日起算；
 * bookFound=false：确认丢书，按赔书价生成赔偿单并计丢书信用扣分。
 */
public record ReaderLiableRequest(
        @NotBlank String staff,
        boolean bookFound,
        String foundLibraryCode,
        @NotBlank String reason
) {}
