package com.group1.banking.scheduler;

import com.group1.banking.entity.*;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.enums.RoleName;
import com.group1.banking.service.AuditService;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.IdempotencyRecordRepository;
import com.group1.banking.repository.StandingOrderRepository;
import com.group1.banking.repository.TransactionQueryRepository;
import com.group1.banking.service.NotificationEvaluationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Standing order execution scheduler. (T054, T055)
 * Processes daily standing orders, handles retries, and triggers notifications on failure.
 */
@Component
public class StandingOrderExecutionJob {

    private final StandingOrderRepository standingOrderRepository;
    private final TransactionQueryRepository transactionQueryRepository;
    private final AccountRepository accountRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final NotificationEvaluationService notificationEvaluationService;
    private final com.group1.banking.util.CanadianHolidayService canadianHolidayService;
    private final AuditService auditService;

    public StandingOrderExecutionJob(StandingOrderRepository standingOrderRepository,
                                      TransactionQueryRepository transactionQueryRepository,
                                      AccountRepository accountRepository,
                                      IdempotencyRecordRepository idempotencyRecordRepository,
                                      NotificationEvaluationService notificationEvaluationService,
                                      com.group1.banking.util.CanadianHolidayService canadianHolidayService,
                                      AuditService auditService) {
        this.standingOrderRepository = standingOrderRepository;
        this.transactionQueryRepository = transactionQueryRepository;
        this.accountRepository = accountRepository;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.notificationEvaluationService = notificationEvaluationService;
        this.canadianHolidayService = canadianHolidayService;
        this.auditService = auditService;
    }

    /**
     * Main daily execution at 00:00:01 UTC — processes orders due today. (T054)
     */
    @Scheduled(cron = "1 0 0 * * *", zone = "UTC")
    @Transactional
    public void processOrders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();

        List<StandingOrderEntity> dueOrders = standingOrderRepository
                .findByStatusAndNextRunDateBefore(StandingOrderStatus.ACTIVE,
                        today.plusDays(1).atStartOfDay());

        for (StandingOrderEntity order : dueOrders) {
            if (order.getNextRunDate().toLocalDate().equals(today)) {
                attemptExecution(order);
            }
        }
    }

    /**
     * First retry attempt at 08:00 UTC — processes RETRY_PENDING orders. (T054)
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "UTC")
    @Transactional
    public void firstAttempt() {
        List<StandingOrderEntity> retryOrders =
                standingOrderRepository.findByStatus(StandingOrderStatus.RETRY_PENDING);
        for (StandingOrderEntity order : retryOrders) {
            if (order.getNextRunDate().toLocalDate().equals(LocalDate.now())) {
                attemptExecution(order);
            }
        }
    }

    /**
     * Final retry attempt at 16:00 UTC — executes remaining RETRY_PENDING. (T054)
     */
    @Scheduled(cron = "0 0 16 * * *", zone = "UTC")
    @Transactional
    public void finalAttempt() {
        List<StandingOrderEntity> retryOrders =
                standingOrderRepository.findByStatus(StandingOrderStatus.RETRY_PENDING);
        for (StandingOrderEntity order : retryOrders) {
            if (order.getNextRunDate().toLocalDate().equals(LocalDate.now())) {
                finalExecutionAttempt(order);
            }
        }
    }

    /**
     * Attempts to execute a standing order. (T055)
     */
    public void attemptExecution(StandingOrderEntity order) {
        String idempotencyKey = order.getStandingOrderId() + ":" + order.getNextRunDate().toLocalDate();

        String storageKey = "SYSTEM:" + idempotencyKey;

        // Idempotency check
        if (idempotencyRecordRepository.findByStorageKey(storageKey).isPresent()) {
            return; // Already processed this cycle
        }

        Account account = accountRepository.findById(order.getSourceAccountId()).orElse(null);
        if (account == null) {
            order.setStatus(StandingOrderStatus.TERMINATED);
            standingOrderRepository.save(order);
            return;
        }

        // Check balance sufficiency
        if (account.getBalance().compareTo(order.getAmount()) < 0) {
            order.setStatus(StandingOrderStatus.RETRY_PENDING);
            standingOrderRepository.save(order);
            return;
        }

        // Execute transfer
        executeTransfer(account, order, idempotencyKey);
    }

    private void finalExecutionAttempt(StandingOrderEntity order) {
        String idempotencyKey = order.getStandingOrderId() + ":" + order.getNextRunDate().toLocalDate();

        Account account = accountRepository.findById(order.getSourceAccountId()).orElse(null);
        if (account == null) {
            order.setStatus(StandingOrderStatus.TERMINATED);
            standingOrderRepository.save(order);
            return;
        }

        if (account.getBalance().compareTo(order.getAmount()) < 0) {
            // Final failure — create FAILED transaction
            Transaction failedTx = new Transaction();
            failedTx.setAccount(account);
            failedTx.setAmount(order.getAmount());
            failedTx.setDirection(TransactionDirection.TRANSFER);
            failedTx.setStatus(TransactionStatus.FAILED);
            failedTx.setDescription("Standing order FAILED: " + order.getStandingOrderId() +
                    " — insufficient funds");
            failedTx.setIdempotencyKey(idempotencyKey);
            transactionQueryRepository.save(failedTx);

            order.setStatus(StandingOrderStatus.FAILED_INSUFFICIENT_FUNDS);
            standingOrderRepository.save(order);

            // Trigger notification
            try {
                notificationEvaluationService.evaluateInternal(
                        "StandingOrderFailure",
                        account.getAccountId(),
                        account.getCustomer().getCustomerId(),
                        LocalDateTime.now().toString(),
                        "Standing order " + order.getStandingOrderId() + " failed — insufficient funds"
                );
            } catch (Exception e) {
                // Log but do not fail the scheduler
                auditService.log(AuditEventType.NOTIFICATION_FAILED,
                        "notifications",
                        RoleName.BANK_ADMINISTRATOR,
                        "-1",
                        "STANDING_ORDER",
                        order.getStandingOrderId(),
                    AuditOutcome.ERROR,
                        null);
            }
            return;
        }

        executeTransfer(account, order, idempotencyKey);
    }

    private void executeTransfer(Account account, StandingOrderEntity order, String idempotencyKey) {
        // Deduct balance
        account.setBalance(account.getBalance().subtract(order.getAmount()));
        accountRepository.save(account);

        // 2. Credit receiver (IMPORTANT MISSING PART)
        Account payeeAccount = accountRepository.findByAccountId(order.getPayeeAccount())
                .orElseThrow(() -> new RuntimeException("Payee account not found"));
 
        payeeAccount.setBalance(payeeAccount.getBalance().add(order.getAmount()));
        accountRepository.save(payeeAccount);

        // Create SUCCESS transaction
        Transaction tx = new Transaction();
        tx.setTransactionId(UUID.randomUUID().toString());
        tx.setAccount(account);
        tx.setAmount(order.getAmount());
        tx.setDirection(TransactionDirection.TRANSFER);
        tx.setStatus(TransactionStatus.SUCCESS);
        tx.setDescription("Standing order: " + order.getPayeeName() + " - " + order.getReference());
        tx.setIdempotencyKey(idempotencyKey);
        transactionQueryRepository.save(tx);

        // Store idempotency record
        IdempotencyRecord record = new IdempotencyRecord();
        record.setStorageKey("SYSTEM:" + idempotencyKey);
        record.setIdempotencyKey(idempotencyKey);
        record.setCallerUserId("SYSTEM");
        record.setOperationType("STANDING_ORDER_EXECUTE");
        record.setResponseBody("SUCCESS");
        record.setResponseStatus(200);
        idempotencyRecordRepository.save(record);

        // Advance nextRunDate
        LocalDateTime nextRun = calculateNextRun(order);
        order.setNextRunDate(nextRun);
        order.setStatus(StandingOrderStatus.ACTIVE);
        standingOrderRepository.save(order);

        auditService.log(AuditEventType.STANDING_ORDER_EXECUTED,
            "standing-orders",
            RoleName.BANK_ADMINISTRATOR,
            "-1",
            "STANDING_ORDER",
            order.getStandingOrderId(),
            AuditOutcome.SUCCESS,
            null);
    }

    private LocalDateTime calculateNextRun(StandingOrderEntity order) {
        LocalDateTime current = order.getNextRunDate();
        LocalDateTime next = switch (order.getFrequency()) {
            case DAILY -> current.plusDays(1);
            case WEEKLY -> current.plusWeeks(1);
            case MONTHLY -> current.plusMonths(1);
            case QUARTERLY -> current.plusMonths(3);
        };
        return canadianHolidayService.nextBusinessDay(next);
    }

}
