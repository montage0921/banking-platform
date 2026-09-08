package com.group1.banking.service;

import com.group1.banking.dto.response.TransactionHistoryResponse;
import com.group1.banking.dto.response.TransactionItemResponse;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.ExportCacheEntity;
import com.group1.banking.entity.Transaction;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.exception.BusinessStateException;
import com.group1.banking.exception.PermissionDeniedException;
import com.group1.banking.exception.ResourceNotFoundException;
import com.group1.banking.exception.RetentionWindowException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.ExportCacheRepository;
import com.group1.banking.repository.TransactionQueryRepository;
import com.group1.banking.security.OwnershipValidator;
import com.group1.banking.security.UserPrincipal;
import com.group1.banking.enums.RoleName;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Transaction history service. (T026, T028)
 */
@Service
public class TransactionHistoryService {

    private static final int MAX_RANGE_DAYS = 366;
    private static final int CLOSED_ACCOUNT_WINDOW_DAYS = 90;

    private final TransactionQueryRepository transactionQueryRepository;
    private final AccountRepository accountRepository;
    private final OwnershipValidator ownershipValidator;
    private final PdfStatementService pdfStatementService;
    private final ExportCacheRepository exportCacheRepository;
    private final AuditService auditService;

    public TransactionHistoryService(TransactionQueryRepository transactionQueryRepository,
                                     AccountRepository accountRepository,
                                     OwnershipValidator ownershipValidator,
                                     PdfStatementService pdfStatementService,
                                     ExportCacheRepository exportCacheRepository,
                                     AuditService auditService) {
        this.transactionQueryRepository = transactionQueryRepository;
        this.accountRepository = accountRepository;
        this.ownershipValidator = ownershipValidator;
        this.pdfStatementService = pdfStatementService;
        this.exportCacheRepository = exportCacheRepository;
        this.auditService = auditService;
    }

    /**
     * Retrieves transaction history for an account within a date range. (T026)
     */
    public TransactionHistoryResponse getHistory(long accountId, LocalDate startDate,
                                                  LocalDate endDate, UserPrincipal caller) {
        if (!caller.hasPermission("CUSTOMER_READ")) {
            throw new PermissionDeniedException("CUSTOMER_READ");
        }
        ownershipValidator.assertOwnership(accountId, caller);

        Instant now = Instant.now();

        // Reject future startDate
        if (startDate != null && startDate.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new BusinessStateException(
                    "startDate must not be in the future", "ERR_FUTURE_START_DATE", "startDate");
        }

        // Reject endDate before startDate
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessStateException(
                    "endDate must be after startDate", "ERR_END_DATE_BEFORE_START", "endDate");
        }

        // Apply defaults
        Instant effectiveStart = (startDate != null)
                ? startDate.atStartOfDay().toInstant(ZoneOffset.UTC)
                : now.minus(28, ChronoUnit.DAYS);
        Instant effectiveEnd = (endDate != null)
                ? endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC)
                : now;

        // Override future endDate silently
        if (effectiveEnd.isAfter(now)) {
            effectiveEnd = now;
        }

        // Validate range <= 366 days
        LocalDate startLocalDate = effectiveStart.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate endLocalDate = effectiveEnd.atZone(ZoneOffset.UTC).toLocalDate();
        long daysBetween = ChronoUnit.DAYS.between(startLocalDate, endLocalDate);
        if (daysBetween > MAX_RANGE_DAYS) {
            throw new BusinessStateException(
                    "Date range must not exceed 366 days", "ERR_DATE_RANGE_EXCEEDED", "endDate");
        }

        // Check account status
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Account not found", "ERR_ACC_NOT_FOUND", null));

        if (account.getStatus() == AccountStatus.CLOSED) {
            Instant closedAt = account.getClosedAt();
            if (closedAt != null && closedAt.isBefore(now.minus(CLOSED_ACCOUNT_WINDOW_DAYS, ChronoUnit.DAYS))) {
                RoleName actorRole;
                try {
                    actorRole = RoleName.valueOf(resolveRole(caller));
                } catch (Exception ex) {
                    actorRole = RoleName.RETAIL_CUSTOMER;
                }
                auditService.log(AuditEventType.TRANSACTION_HISTORY_DENIED,
                            "transaction-history",
                            actorRole,
                            caller.getUserId(),
                            "ACCOUNT",
                            String.valueOf(accountId),
                            AuditOutcome.DENIED,
                            null);
                throw new RetentionWindowException(
                        "Account closed and 90-day window has expired", "ERR_RETENTION_WINDOW");
            }
        }

        List<Transaction> transactions =
                transactionQueryRepository.findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(
                        accountId, effectiveStart, effectiveEnd);

        List<TransactionItemResponse> items = transactions.stream()
                .map(this::toItemResponse)
                .collect(Collectors.toList());

        com.group1.banking.enums.RoleName actorRole2;
        try {
            actorRole2 = com.group1.banking.enums.RoleName.valueOf(resolveRole(caller));
        } catch (Exception ex) {
            actorRole2 = com.group1.banking.enums.RoleName.RETAIL_CUSTOMER;
        }
        auditService.log(com.group1.banking.entity.AuditEventType.TRANSACTION_HISTORY_VIEWED,
            "transaction-history",
            actorRole2,
            caller.getUserId(),
            "ACCOUNT",
            String.valueOf(accountId),
            com.group1.banking.entity.AuditOutcome.SUCCESS,
            null);

        TransactionHistoryResponse response = new TransactionHistoryResponse();
        response.setAccountId(accountId);
        response.setStartDate(effectiveStart);
        response.setEndDate(effectiveEnd);
        response.setTransactionCount(items.size());
        response.setTransactions(items);
        return response;
    }

    /**
     * Exports transaction history as a PDF with idempotent caching. (T028)
     */
    public byte[] exportPdf(long accountId, LocalDate startDate,
                             LocalDate endDate, UserPrincipal caller) {
        if (!caller.hasPermission("CUSTOMER_READ")) {
            throw new PermissionDeniedException("CUSTOMER_READ");
        }
        ownershipValidator.assertOwnership(accountId, caller);

        Instant now = Instant.now();
        Instant effectiveStart = (startDate != null)
                ? startDate.atStartOfDay().toInstant(ZoneOffset.UTC)
                : now.minus(28, ChronoUnit.DAYS);
        Instant rawEffectiveEnd = (endDate != null)
                ? endDate.atTime(23, 59, 59).toInstant(ZoneOffset.UTC)
                : now;
        Instant effectiveEnd = rawEffectiveEnd.isAfter(now) ? now : rawEffectiveEnd;

        String paramHash = computeHash(accountId, effectiveStart, effectiveEnd);

        // Check cache
        return exportCacheRepository.findByAccountIdAndParamHash(accountId, paramHash)
            .map(ExportCacheEntity::getPdfData)
            .orElseGet(() -> {
                    List<Transaction> txns =
                            transactionQueryRepository
                                    .findByAccount_AccountIdAndTimestampBetweenOrderByTimestampAsc(
                                            accountId, effectiveStart, effectiveEnd);
                    byte[] pdfBytes = pdfStatementService.buildPdf(
                            accountId,
                            effectiveStart.atZone(ZoneOffset.UTC).toLocalDate(),
                            effectiveEnd.atZone(ZoneOffset.UTC).toLocalDate(),
                            txns);

                    ExportCacheEntity cache = new ExportCacheEntity();
                    cache.setAccountId(accountId);
                    cache.setParamHash(paramHash);
                    cache.setPdfData(pdfBytes);
                    exportCacheRepository.save(cache);
                // Audit: transaction history exported
                com.group1.banking.enums.RoleName actorRoleEnum;
                try {
                actorRoleEnum = com.group1.banking.enums.RoleName.valueOf(resolveRole(caller));
                } catch (Exception ex) {
                actorRoleEnum = com.group1.banking.enums.RoleName.RETAIL_CUSTOMER;
                }
                auditService.log(com.group1.banking.entity.AuditEventType.TRANSACTION_HISTORY_EXPORTED,
                    "export",
                    actorRoleEnum,
                    caller.getUserId(),
                    "ACCOUNT",
                    String.valueOf(accountId),
                    AuditOutcome.SUCCESS,
                    paramHash);
                    return pdfBytes;
                });
    }

    private String computeHash(long accountId, Instant start, Instant end) {
        String input = accountId
                + "|" + start.atZone(ZoneOffset.UTC).toLocalDate()
                + "|" + end.atZone(ZoneOffset.UTC).toLocalDate();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String resolveRole(UserPrincipal caller) {
        List<String> roles = caller.getRoles();
        if (roles != null && !roles.isEmpty()) {
            return roles.get(0);
        }
        return "UNKNOWN";
    }

    private TransactionItemResponse toItemResponse(Transaction t) {
        TransactionItemResponse item = new TransactionItemResponse();
        item.setTransactionId(t.getTransactionId());
        item.setAmount(t.getAmount());
        item.setDirection(t.getDirection() != null ? t.getDirection().name() : null);
        item.setStatus(t.getStatus().name());
        item.setTimestamp(t.getTimestamp());
        item.setDescription(t.getDescription());
        item.setIdempotencyKey(t.getIdempotencyKey());
        item.setCategory(t.getCategory());
        item.setSenderInfo(t.getSenderInfo());
        item.setReceiverInfo(t.getReceiverInfo());
        item.setExternalTransactionId(t.getExternalTransactionId());
        return item;
    }
}