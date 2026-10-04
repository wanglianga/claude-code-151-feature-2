package com.example.library.web;

import com.example.library.service.SelfCheckinService;
import com.example.library.web.dto.KioskLogRequest;
import com.example.library.web.dto.KioskLogView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/kiosk")
@Tag(name = "5b. 自助还书机日志",
        description = "投书即记录，扫描成功或失败均留痕；申诉时按条码/分馆与时间窗调取核查")
public class KioskController {

    private final SelfCheckinService kiosk;

    public KioskController(SelfCheckinService kiosk) {
        this.kiosk = kiosk;
    }

    @PostMapping("/logs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "上报一条还书机日志（SCAN_SUCCESS / SCAN_FAILED；失败时条码可空，保留书箱号与监控编号）")
    public KioskLogView record(@Valid @RequestBody KioskLogRequest req) {
        return com.example.library.web.Views.kioskLog(kiosk.record(req.machineCode(), req.libraryCode(),
                req.itemBarcode(), req.result(), req.occurredAt(), req.binNo(), req.cameraRef(), req.note()));
    }

    @GetMapping("/logs")
    @Operation(summary = "核查查询：按 barcode 或 libraryCode + 时间窗（默认近 30 天）")
    public List<KioskLogView> query(
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) String libraryCode,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return kiosk.views(barcode, libraryCode, from, to);
    }
}
