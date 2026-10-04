package com.example.library.web;

import com.example.library.domain.Loan;
import com.example.library.service.LoanService;
import com.example.library.web.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/loans")
@Tag(name = "4. 借阅与还书", description = "借出、应还日期、续借资格/排队冲突、还书核验（污损/缺页/附件）")
public class LoanController {

    private final LoanService loans;

    public LoanController(LoanService loans) {
        this.loans = loans;
    }

    @PostMapping("/checkouts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "借出；预约架上的图书只能借给对应预约者，有逾期未还时拒绝借书")
    public LoanView checkout(@Valid @RequestBody CheckoutRequest req) {
        return loans.checkoutView(req.readerCardNo(), req.itemBarcode(), req.libraryCode());
    }

    @GetMapping("/{loanId}/renew-eligibility")
    @Operation(summary = "查询续借资格：剩余次数、是否有排队冲突及规则依据")
    public Map<String, Object> eligibility(@PathVariable Long loanId) {
        Loan l = loans.get(loanId);
        LoanService.RenewCheck c = loans.renewEligibility(l);
        return Map.of(
                "renewable", c.renewable(),
                "reason", c.reason() == null ? "" : c.reason(),
                "ruleBasis", c.ruleBasis() == null ? "" : c.ruleBasis(),
                "dueDate", l.getDueDate(),
                "renewCount", l.getRenewCount()
        );
    }

    @PostMapping("/{loanId}/renewals")
    @Operation(summary = "办理续借；达到次数上限或该书有读者排队时拒绝")
    public LoanView renew(@PathVariable Long loanId) {
        return loans.renewView(loanId);
    }

    @PostMapping("/returns")
    @Operation(summary = "工作人员还书核验：条码 + 馆藏状态 + 污损/缺页/附件；完好图书自动给排队者或上架")
    public LoanView returnBook(@Valid @RequestBody ReturnRequest req) {
        return loans.returnView(req.itemBarcode(), req.libraryCode(),
                new LoanService.ReturnAssessment(req.damaged(), req.missingPages(),
                        req.accessoryMissing(), req.note()));
    }

    @PostMapping("/jobs/daily-overdue")
    @Operation(summary = "（馆员/定时任务手动触发）逾期罚款累计与到期提醒；争议申诉中的借阅跳过（罚款冻结）")
    public int dailyOverdueJob() {
        return loans.runDailyOverdueJob();
    }

    @GetMapping("/readers/{cardNo}/active")
    @Operation(summary = "读者当前借阅")
    public List<LoanView> active(@PathVariable String cardNo) {
        return loans.activeLoanViews(cardNo);
    }

    @GetMapping("/{loanId}")
    @Operation(summary = "借阅单详情")
    public LoanView get(@PathVariable Long loanId) {
        return loans.loanView(loanId);
    }
}
