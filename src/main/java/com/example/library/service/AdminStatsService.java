package com.example.library.service;

import com.example.library.domain.*;
import com.example.library.repo.*;
import com.example.library.web.ApiException;
import com.example.library.web.dto.admin.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理端报表：按馆 / 书目 / 读者 / 物流批次查看调拨效率、热门预约、逾期与丢损。 */
@Service
public class AdminStatsService {

    private final TransferTaskRepository tasks;
    private final TransferBatchRepository batches;
    private final HoldRepository holds;
    private final LoanRepository loans;
    private final FineRepository fines;
    private final ItemRepository items;
    private final BibRepository bibs;
    private final LibraryRepository libraries;
    private final CreditService credit;

    public AdminStatsService(TransferTaskRepository tasks, TransferBatchRepository batches,
                             HoldRepository holds, LoanRepository loans, FineRepository fines,
                             ItemRepository items, BibRepository bibs, LibraryRepository libraries,
                             CreditService credit) {
        this.tasks = tasks;
        this.batches = batches;
        this.holds = holds;
        this.loans = loans;
        this.fines = fines;
        this.items = items;
        this.bibs = bibs;
        this.libraries = libraries;
        this.credit = credit;
    }

    @Transactional(readOnly = true)
    public List<LibraryStats> libraryStats(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<TransferTask> picked = tasks.findPickedSince(since);
        List<Loan> activeLoans = loans.findByStatusIn(List.of(LoanStatus.ACTIVE));
        Instant now = Instant.now();

        return libraries.findAll().stream()
                .filter(Library::isActive)
                .map(lib -> {
                    long available = items.countByCurrentLibraryIdAndStatus(
                            lib.getId(), ItemStatus.AVAILABLE);
                    List<TransferTask> outgoing = picked.stream()
                            .filter(t -> t.getFromLibrary().getId().equals(lib.getId())).toList();
                    List<TransferTask> incoming = picked.stream()
                            .filter(t -> t.getToLibrary().getId().equals(lib.getId())).toList();
                    long inTransitOut = outgoing.stream()
                            .filter(t -> t.getStatus() == TaskStatus.PICKED_UP).count();
                    long lostTask = picked.stream()
                            .filter(t -> t.getStatus() == TaskStatus.LOST
                                    && (t.getFromLibrary().getId().equals(lib.getId())
                                    || t.getToLibrary().getId().equals(lib.getId())))
                            .count();
                    long activeHoldsHere = holds.findActiveAtPickup(lib.getId(),
                            List.of(HoldStatus.QUEUED, HoldStatus.READY)).size();
                    long overdueLoans = activeLoans.stream()
                            .filter(l -> l.getCheckoutLibrary().getId().equals(lib.getId())
                                    && l.getDueDate().isBefore(now))
                            .count();
                    return new LibraryStats(lib.getCode(), lib.getName(),
                            available, inTransitOut, lostTask, activeHoldsHere, overdueLoans,
                            outgoing.size(), incoming.size(),
                            round(avgTransitHours(outgoing)), round(avgTransitHours(incoming)));
                }).toList();
    }

    // countByBibIdAndCurrentLibraryIdAndStatus 不支持 bibId 为空，故单独仓储计数
    @Transactional(readOnly = true)
    public LibraryStats singleLibrary(String code, int days) {
        return libraryStats(days).stream()
                .filter(s -> s.code().equals(code))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("分馆不存在: " + code));
    }

    private double avgTransitHours(List<TransferTask> list) {
        return list.stream()
                .filter(t -> t.getArrivedAt() != null && t.getPickedAt() != null)
                .mapToLong(t -> Duration.between(t.getPickedAt(), t.getArrivedAt()).toMinutes())
                .average().orElse(0) / 60.0;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    @Transactional(readOnly = true)
    public List<PopularBibRow> popularBibs(int days, int limit) {
        return holds.countPopularSince(Instant.now().minus(days, ChronoUnit.DAYS)).stream()
                .limit(limit)
                .map(row -> new PopularBibRow(
                        (Long) row[0], (String) row[1], ((Number) row[2]).longValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public BibStats bibStats(Long bibId) {
        Bib bib = bibs.findById(bibId).orElseThrow(() -> ApiException.notFound("书目不存在: " + bibId));
        long totalCopies = items.findAll().stream().filter(i -> i.getBib().getId().equals(bibId)).count();
        Map<String, Long> byStatus = items.findAll().stream()
                .filter(i -> i.getBib().getId().equals(bibId))
                .collect(Collectors.groupingBy(i -> i.getStatus().name(), Collectors.counting()));
        long queue = holds.findActiveByBib(bibId, List.of(HoldStatus.QUEUED, HoldStatus.READY)).size();
        long activeLoans = loans.findByStatusIn(List.of(LoanStatus.ACTIVE)).stream()
                .filter(l -> l.getItem().getBib().getId().equals(bibId)).count();
        return new BibStats(bib.getId(), bib.getIsbn(), bib.getTitle(), bib.getAuthor(),
                totalCopies, byStatus, queue, activeLoans, bib.isEbookAvailable());
    }

    @Transactional(readOnly = true)
    public List<ReaderStatsRow> readerOverview(String cardNo) {
        List<Loan> all = loans.findAll();
        List<Fine> allFines = fines.findAll();
        return all.stream()
                .map(Loan::getReader)
                .distinct()
                .filter(r -> cardNo == null || r.getCardNo().equals(cardNo))
                .map(r -> {
                    List<Loan> readerLoans = all.stream()
                            .filter(l -> l.getReader().getId().equals(r.getId())).toList();
                    long active = readerLoans.stream().filter(l -> l.getStatus() == LoanStatus.ACTIVE).count();
                    long overdue = readerLoans.stream()
                            .filter(l -> l.getStatus() == LoanStatus.ACTIVE
                                    && l.getDueDate().isBefore(Instant.now()))
                            .count();
                    long claims = readerLoans.stream()
                            .filter(l -> l.getStatus() == LoanStatus.CLAIMED_RETURNED).count();
                    List<Fine> readerFines = allFines.stream()
                            .filter(f -> f.getReader() != null && f.getReader().getId().equals(r.getId())).toList();
                    long openFines = readerFines.stream().filter(f -> f.getStatus() == FineStatus.OPEN).count();
                    double openAmount = readerFines.stream()
                            .filter(f -> f.getStatus() == FineStatus.OPEN)
                            .mapToDouble(f -> f.getAmount().doubleValue()).sum();
                    long queued = holds.findActiveByReaderCard(r.getCardNo(),
                            List.of(HoldStatus.QUEUED, HoldStatus.READY)).size();
                    return new ReaderStatsRow(r.getCardNo(), r.getName(), queued, active, overdue, claims,
                            openFines, round(openAmount), credit.score(r.getCardNo()));
                })
                .sorted(Comparator.comparingDouble(ReaderStatsRow::openFineAmount).reversed())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BatchStatsRow> batchStats() {
        return batches.findAll().stream()
                .sorted(Comparator.comparing(TransferBatch::getCreatedAt).reversed())
                .map(b -> {
                    List<TransferTask> ts = tasks.findByBatchId(b.getId());
                    long arrived = ts.stream().filter(t -> t.getStatus() == TaskStatus.ARRIVED).count();
                    long lost = ts.stream().filter(t -> t.getStatus() == TaskStatus.LOST).count();
                    long inProgress = ts.stream()
                            .filter(t -> t.getStatus() == TaskStatus.PICKED_UP || t.getStatus() == TaskStatus.CREATED)
                            .count();
                    return new BatchStatsRow(b.getId(), b.getBatchNo(), b.getStaffName(), b.getStatus(),
                            b.getCreatedAt(), b.getClosedAt(),
                            ts.size(), arrived, lost, inProgress, round(avgTransitHours(ts)));
                }).toList();
    }

    @Transactional(readOnly = true)
    public OverallDashboard dashboard(int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        List<TransferTask> picked = tasks.findPickedSince(since);
        long arrived = picked.stream().filter(t -> t.getStatus() == TaskStatus.ARRIVED).count();
        long lost = picked.stream().filter(t -> t.getStatus() == TaskStatus.LOST).count();
        long overdue = loans.findOverdue(LoanStatus.ACTIVE, Instant.now()).size();
        Map<String, Long> finesByType = fines.findAll().stream()
                .filter(f -> f.getCreatedAt().isAfter(since))
                .collect(Collectors.groupingBy(f -> f.getType().name(), Collectors.counting()));
        double openFineTotal = fines.findByStatusOrderByCreatedAt(FineStatus.OPEN).stream()
                .mapToDouble(f -> f.getAmount().doubleValue()).sum();
        return new OverallDashboard(days, picked.size(), arrived, lost,
                picked.isEmpty() ? 0 : round(100.0 * arrived / picked.size()),
                overdue, finesByType, round(openFineTotal));
    }
}
