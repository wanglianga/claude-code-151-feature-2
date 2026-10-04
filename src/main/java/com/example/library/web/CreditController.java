package com.example.library.web;

import com.example.library.service.CreditService;
import com.example.library.web.dto.CreditEventView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/credit")
@Tag(name = "5c. 借阅信用",
        description = "信用分初始 100：逾期/丢书计负，申诉处理期间不变化；确认馆方漏入库时同案修复")
public class CreditController {

    private final CreditService credit;

    public CreditController(CreditService credit) {
        this.credit = credit;
    }

    @GetMapping("/readers/{cardNo}")
    @Operation(summary = "读者信用分（=100 + 全部信用流水之和）")
    public Map<String, Object> score(@PathVariable String cardNo) {
        return Map.of("readerCardNo", cardNo, "creditScore", credit.score(cardNo));
    }

    @GetMapping("/readers/{cardNo}/events")
    @Operation(summary = "读者信用流水（逾期/丢书扣减、馆方责任修复 REPAIR）")
    public List<CreditEventView> events(@PathVariable String cardNo) {
        return credit.viewsForReader(cardNo);
    }
}
