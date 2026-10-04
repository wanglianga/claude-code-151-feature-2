package com.example.library.web;

import com.example.library.service.KioskService;
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
@RequestMapping("/api/kiosk-logs")
@Tag(name = "7. 自助还书机日志",
        description = "设备上报扫描失败/书箱卡滞/正常回收日志，供“已还未入库”申诉核查")
public class KioskController {

    private final KioskService kiosks;

    public KioskController(KioskService kiosks) {
        this.kiosks = kiosks;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "自助机上报运行日志（SCAN_FAIL 扫描失败 / BIN_JAM 书箱卡滞 / OK 正常回收）")
    public KioskLogView ingest(@Valid @RequestBody KioskLogRequest req) {
        return KioskLogView.from(kiosks.ingest(req.libraryCode(), req.kioskCode(), req.eventType(),
                req.itemBarcode(), req.readerCardNo(), req.occurredAt(), req.detail()));
    }

    @GetMapping
    @Operation(summary = "（馆员）核查机日志：按条码，或按分馆 + 时间窗查询")
    public List<KioskLogView> query(@RequestParam(required = false) String itemBarcode,
                                    @RequestParam(required = false) String libraryCode,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to) {
        return kiosks.query(itemBarcode, libraryCode, from, to);
    }
}
