package com.group1.banking.service;

import com.group1.banking.dto.SavingsGoalRequest;
import com.group1.banking.dto.SavingsGoalResponse;
import com.group1.banking.entity.*;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.enums.SavingsGoalStatus;
import com.group1.banking.exception.BusinessException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.SavingsGoalRepository;
import com.group1.banking.enums.RoleName;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.SavingsGoal;

/**
 * Savings Goal Service
 * 
 * Implements core business logic:
 * - Progress calculation: (account.balance / target_amount) * 100, capped at 100%
 * - Status derivation: NOT_STARTED, IN_PROGRESS, ACHIEVED, OVERDUE
 * - Validation: target_amount > 0, target_date >= today, goal_name not empty
 * - CRUD operations with soft delete pattern
 * 
 * All derived fields (progress, status, time_remaining) are calculated on every read
 * to ensure accuracy as account.balance changes independently.
 */
@Service
@Transactional
public class SavingsGoalService {
    
    private final SavingsGoalRepository savingsGoalRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public SavingsGoalService(SavingsGoalRepository savingsGoalRepository,
                               AccountRepository accountRepository,
                               AuditService auditService) {
        this.savingsGoalRepository = savingsGoalRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }
    
    /**
     * Create a new savings goal for a customer's account
     * 
     * Validation:
     * - Account must exist and be active (deleted_at IS NULL)
     * - No existing active goal for this account
     * - target_amount > 0
     * - target_date >= today
     * - goal_name not empty
     */
    public SavingsGoalResponse createGoal(Long customerId, Long accountId, SavingsGoalRequest request) {
        // 1. Validate account exists and is active
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException("ACCOUNT_NOT_FOUND", 
                    "Account not found or inactive"));
        
        // 2. Verify ownership
        if (!account.getCustomer().getCustomerId().equals(customerId)) {
            throw new BusinessException("UNAUTHORIZED_ACCOUNT_ACCESS", 
                "You do not have permission to access this account");
        }
        
        // 3. Validate no existing active goal
        if (savingsGoalRepository.findActiveByCustomerIdAndAccountId(customerId, accountId).isPresent()) {
            throw new BusinessException("GOAL_ALREADY_EXISTS", 
                "An active goal already exists for this account");
        }
        
        // 4. Validate request fields
        validateGoalRequest(request);
        
        // 5. Create and save goal
        SavingsGoal goal = new SavingsGoal();
        goal.setCustomerId(customerId);
        goal.setAccount(account);
        goal.setGoalName(request.getGoalName());
        goal.setTargetAmount(request.getTargetAmount());
        goal.setTargetDate(request.getTargetDate());
        goal.setStatus(SavingsGoalStatus.NOT_STARTED);
        goal.setDeletedAt(null);
        
        SavingsGoal savedGoal = savingsGoalRepository.save(goal);
        
        auditService.log(AuditEventType.SAVINGS_GOAL_CREATED,
            "savings-goals",
            RoleName.RETAIL_CUSTOMER,
            customerId.toString(),
            "SAVINGS_GOAL",
            String.valueOf(savedGoal.getGoalId()),
            AuditOutcome.SUCCESS,
            null);
        
        // 6. Enrich with derived fields and return
        return enrichGoalWithDerivedFields(savedGoal, account);
    }
    
    /**
     * Retrieve a single goal for a customer
     */
    @Transactional(readOnly = true)
    public SavingsGoalResponse getGoal(Long customerId, Long goalId) {
        SavingsGoal goal = savingsGoalRepository.findByGoalIdAndCustomerId(goalId, customerId)
                .orElseThrow(() -> new BusinessException("GOAL_NOT_FOUND", 
                    "Goal not found"));
        
        Account account = goal.getAccount();
        return enrichGoalWithDerivedFields(goal, account);
    }
    
    /**
     * Retrieve all goals for a customer (bulk fetch)
     */
    @Transactional(readOnly = true)
    public List<SavingsGoalResponse> getAllGoalsForCustomer(Long customerId) {
        List<SavingsGoal> goals = savingsGoalRepository.findAllActiveByCustomerId(customerId);
        return goals.stream()
                .map(goal -> enrichGoalWithDerivedFields(goal, goal.getAccount()))
                .collect(Collectors.toList());
    }
    
    /**
     * Update an existing goal
     * 
     * Allows editing:
     * - goal_name
     * - target_amount
     * - target_date
     * 
     * Updates updated_at timestamp
     */
    public SavingsGoalResponse updateGoal(Long customerId, Long goalId, SavingsGoalRequest request) {
        SavingsGoal goal = savingsGoalRepository.findByGoalIdAndCustomerId(goalId, customerId)
                .orElseThrow(() -> new BusinessException("GOAL_NOT_FOUND", 
                    "Goal not found"));
        
        // Validate request
        validateGoalRequest(request);
        
        // Update fields
        goal.setGoalName(request.getGoalName());
        goal.setTargetAmount(request.getTargetAmount());
        goal.setTargetDate(request.getTargetDate());
        
        SavingsGoal updatedGoal = savingsGoalRepository.save(goal);
        
        auditService.log(AuditEventType.UPDATE_SAVINGS_GOAL,
            "savings-goals",
            com.group1.banking.enums.RoleName.RETAIL_CUSTOMER,
            customerId.toString(),
            "SAVINGS_GOAL",
            String.valueOf(goalId),
            AuditOutcome.SUCCESS,
            null);
        
        return enrichGoalWithDerivedFields(updatedGoal, updatedGoal.getAccount());
    }
    
    /**
     * Soft delete a goal
     * Sets deleted_at = NOW()
     */
    public void deleteGoal(Long customerId, Long goalId) {
        SavingsGoal goal = savingsGoalRepository.findByGoalIdAndCustomerId(goalId, customerId)
                .orElseThrow(() -> new BusinessException("GOAL_NOT_FOUND", 
                    "Goal not found"));
        
          savingsGoalRepository.delete(goal);     
        
        auditService.log(AuditEventType.DELETE_SAVINGS_GOAL,
            "savings-goals",
            RoleName.RETAIL_CUSTOMER,
            customerId.toString(),
            "SAVINGS_GOAL",
            String.valueOf(goalId),
            AuditOutcome.SUCCESS,
            null);
    }
    
    /**
     * Validate goal request
     * Throws BusinessException if validation fails
     */
    private void validateGoalRequest(SavingsGoalRequest request) {
        if (request.getGoalName() == null || request.getGoalName().isBlank()) {
            throw new BusinessException("INVALID_GOAL_NAME", 
                "Goal name cannot be empty");
        }
        
        if (request.getTargetAmount() == null || request.getTargetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("INVALID_TARGET_AMOUNT", 
                "Target amount must be greater than $0");
        }
        
        if (request.getTargetDate() == null || request.getTargetDate().isBefore(LocalDate.now())) {
            throw new BusinessException("INVALID_TARGET_DATE", 
                "Target date must be today or in the future");
        }
    }
    
    /**
     * Enrich goal with derived fields (progress, status, time_remaining)
     * Calculated on every read to ensure accuracy
     */
    private SavingsGoalResponse enrichGoalWithDerivedFields(SavingsGoal goal, Account account) {
        BigDecimal currentBalance = account.getBalance() != null ? account.getBalance() : BigDecimal.ZERO;
        BigDecimal progressPercentage = calculateProgress(currentBalance, goal.getTargetAmount());
        SavingsGoalStatus status = deriveStatus(currentBalance, goal.getTargetAmount(), goal.getTargetDate());
        Long timeRemaining = calculateTimeRemaining(goal.getTargetDate());
        
        return SavingsGoalResponse.builder()
                .goalId(goal.getGoalId())
                .accountId(goal.getAccount().getAccountId())
                .accountNumber(goal.getAccount().getAccountNumber())
                .accountType(goal.getAccount().getAccountType().name())
                .goalName(goal.getGoalName())
                .targetAmount(goal.getTargetAmount())
                .targetDate(goal.getTargetDate())
                .currentBalance(currentBalance)
                .progressPercentage(progressPercentage)
                .timeRemainingDays(timeRemaining)
                .status(status)
                .createdAt(convertInstantToZonedDateTime(goal.getCreatedAt()))
                .updatedAt(convertInstantToZonedDateTime(goal.getUpdatedAt()))
                .build();
    }
    
    /**
     * Calculate progress percentage
     * Formula: (currentBalance / targetAmount) * 100
     * Capped at 100% (never exceeds 100)
     */
    private BigDecimal calculateProgress(BigDecimal currentBalance, BigDecimal targetAmount) {
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal progress = currentBalance.divide(targetAmount, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        
        // Cap at 100%
        if (progress.compareTo(new BigDecimal("100")) > 0) {
            progress = new BigDecimal("100");
        }
        
        return progress;
    }
    
    /**
     * Derive goal status based on balance and target date
     * 
     * NOT_STARTED: balance = 0 AND target_date >= today
     * IN_PROGRESS: balance > 0 AND balance < target AND target_date >= today
     * ACHIEVED: balance >= target
     * OVERDUE: target_date < today AND balance < target
     */
    private SavingsGoalStatus deriveStatus(BigDecimal currentBalance, BigDecimal targetAmount, LocalDate targetDate) {
        LocalDate today = LocalDate.now();
        
        if (currentBalance.compareTo(targetAmount) >= 0) {
            return SavingsGoalStatus.ACHIEVED;
        }
        
        if (targetDate.isBefore(today) && currentBalance.compareTo(targetAmount) < 0) {
            return SavingsGoalStatus.OVERDUE;
        }
        
        if (currentBalance.compareTo(BigDecimal.ZERO) > 0) {
            return SavingsGoalStatus.IN_PROGRESS;
        }
        
        return SavingsGoalStatus.NOT_STARTED;
    }
    
    /**
     * Calculate time remaining in days
     * targetDate - today
     * Never negative (capped at 0 if overdue)
     */
    private Long calculateTimeRemaining(LocalDate targetDate) {
        LocalDate today = LocalDate.now();
        if (targetDate.isBefore(today)) {
            return 0L;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(today, targetDate);
    }
    
    /**
     * Convert Instant to ZonedDateTime (UTC)
     */
    private ZonedDateTime convertInstantToZonedDateTime(Instant instant) {
        return instant.atZone(ZoneId.of("UTC"));
    }
}
