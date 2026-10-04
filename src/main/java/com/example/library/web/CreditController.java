package com.example.library.web;

import com.example.library.domain.Reader;
import com.example.library.repo.ReaderRepository;
import com.example.library.service.CreditService;
import com.example.library.web.ApiException;
import com.example.library.web.dto.CreditOverviewView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/credit")
@Tag(name = "8. 借阅信用",
        description = "逾期/丢书扣减；已还未入库申诉核查期间不立即恢复，确认馆方漏入库时随结案修复")
public class CreditController {

    private final CreditService credit;
    private final ReaderRepository readers;

    public CreditController(CreditService credit, ReaderRepository readers) {
        this.credit = credit;
        this.readers = readers;
    }

    @GetMapping("/readers/{cardNo}")
    @Operation(summary = "读者借阅信用概览：当前总分与变动流水（扣减/修复）")
    public CreditOverviewView overview(@PathVariable String cardNo) {
        Reader r = readers.findByCardNo(cardNo)
                .orElseThrow(() -> ApiException.notFound("读者证不存在: " + cardNo));
        return new CreditOverviewView(r.getCardNo(), r.getName(),
                credit.score(r.getId()), credit.viewsForReader(r.getId()));
    }
}
