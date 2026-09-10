package com.group1.banking.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.group1.banking.dto.gic.CreateGicRequest;
import com.group1.banking.dto.gic.GicResponse;
import com.group1.banking.dto.gic.RedeemGicResponse;
import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;
import com.group1.banking.entity.GicInvestment;
import com.group1.banking.entity.GicStatus;
import com.group1.banking.entity.User;
import com.group1.banking.exception.BadRequestException;
import com.group1.banking.exception.NotFoundException;
import com.group1.banking.exception.UnauthorisedException;
import com.group1.banking.repository.AccountRepository;
import com.group1.banking.repository.GicRepository;
import com.group1.banking.repository.UserRepository;
import com.group1.banking.security.CustomUserPrincipal;
import com.group1.banking.service.AuditService;
import com.group1.banking.entity.AuditEventType;
import com.group1.banking.entity.AuditOutcome;
import com.group1.banking.enums.RoleName;

@Service
public class GicService {

    private final GicRepository gicRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public GicService(
            GicRepository gicRepository,
            AccountRepository accountRepository,
            UserRepository userRepository,
            AuditService auditService) {
        this.gicRepository = gicRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    // region Public API

    @Transactional
    public GicResponse createGic(Long accountId, CreateGicRequest request) {
        User user = getAuthenticatedUser();
        Account account = loadActiveRrspAccount(accountId);
        checkAuthorization(user, account);

        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        if (account.getBalance().compareTo(amount) < 0) {
            throw new BadRequestException("INSUFFICIENT_FUNDS",
                    "Insufficient RRSP balance to create GIC.",
                    Map.of("available", account.getBalance(), "requested", amount));
        }

        LocalDate startDate = LocalDate.now();
        BigDecimal interestRate = request.term().getAnnualRate();
        LocalDate maturityDate = request.term().computeMaturityDate(startDate);
        BigDecimal maturityAmount = request.term().computeMaturityAmount(amount);

        GicInvestment gic = new GicInvestment();
        gic.setAccount(account);
        gic.setPrincipalAmount(amount);
        gic.setInterestRate(interestRate);
        gic.setTerm(request.term());
        gic.setStartDate(startDate);
        gic.setMaturityDate(maturityDate);
        gic.setMaturityAmount(maturityAmount);
        gic.setStatus(GicStatus.ACTIVE);

        // Deduct amount from RRSP balance
        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);

        GicInvestment saved = gicRepository.save(gic);

        RoleName actorRole = isAdmin(user) ? RoleName.BANK_ADMINISTRATOR : RoleName.RETAIL_CUSTOMER;
        auditService.log(AuditEventType.GIC_CREATED,
            "gic",
            actorRole,
            user.getUserId().toString(),
            "GIC",
            saved.getGicId(),
            AuditOutcome.SUCCESS,
            null);

        return GicResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<GicResponse> getGics(Long accountId) {
        User user = getAuthenticatedUser();
        Account account = loadActiveRrspAccount(accountId);
        checkAuthorization(user, account);

        return gicRepository
                .findAllByAccount_AccountIdAndDeletedAtIsNull(accountId)
                .stream()
                .map(GicResponse::from)
                .toList();
    }

    @Transactional
    public RedeemGicResponse redeemGic(Long accountId, String gicId) {
        User user = getAuthenticatedUser();
        Account account = loadActiveRrspAccount(accountId);
        checkAuthorization(user, account);

        GicInvestment gic = gicRepository
                .findByGicIdAndAccount_AccountIdAndDeletedAtIsNull(gicId, accountId)
                .orElseThrow(() -> new NotFoundException(
                        "GIC_NOT_FOUND",
                        "GIC not found for this account.",
                        Map.of("accountId", accountId, "gicId", gicId)));

        if (gic.getStatus() != GicStatus.ACTIVE) {
            throw new BadRequestException("GIC_ALREADY_REDEEMED",
                    "This GIC has already been redeemed.", Map.of("gicId", gicId));
        }

        BigDecimal payout = gic.getMaturityAmount();

        // Credit payout back to RRSP balance
        account.setBalance(account.getBalance().add(payout));
        accountRepository.save(account);

        // Soft-delete: set status to REDEEMED and stamp deletedAt (matches Account pattern)
        gic.setStatus(GicStatus.REDEEMED);
        gic.setDeletedAt(Instant.now());
        gicRepository.save(gic);

        RoleName actorRole2 = isAdmin(user) ? RoleName.BANK_ADMINISTRATOR : RoleName.RETAIL_CUSTOMER;
        auditService.log(AuditEventType.GIC_REDEEMED,
            "gic",
            actorRole2,
            user.getUserId().toString(),
            "GIC",
            gic.getGicId(),
            AuditOutcome.SUCCESS,
            null);

        return new RedeemGicResponse("GIC redeemed successfully.", payout);
    }

    // endregion

    // region Private helpers

    private Account loadActiveRrspAccount(Long accountId) {
        Account account = accountRepository.findByAccountIdAndDeletedAtIsNull(accountId)
                .filter(a -> a.getStatus() == AccountStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "ACCOUNT_NOT_FOUND",
                        "Account not found.",
                        Map.of("accountId", accountId)));

        if (account.getAccountType() != AccountType.RRSP) {
            throw new BadRequestException(
                    "INVALID_ACCOUNT_TYPE_FOR_GIC",
                    "GIC investments are only allowed for RRSP accounts.",
                    Map.of("accountType", account.getAccountType()));
        }

        return account;
    }

    private void checkAuthorization(User user, Account account) {
        if (!isAdmin(user) && !user.getCustomerId().equals(account.getCustomer().getCustomerId())) {
            throw new UnauthorisedException("UNAUTHORIZED", "You can only manage your own RRSP accounts.");
        }
    }

    private User getAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication.getPrincipal() instanceof CustomUserPrincipal principal)) {
            throw new UnauthorisedException("UNAUTHORIZED", "Authenticated user not found.");
        }
        UUID userId = principal.getUserId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorisedException("UNAUTHORIZED", "Authenticated user not found."));
    }

    private boolean isAdmin(User user) {
        return user.getRoles().stream()
                .anyMatch(r -> r.name().equalsIgnoreCase("BANK_ADMINISTRATOR") || r.name().equalsIgnoreCase("ROLE_BANK_ADMINISTRATOR"));
    }

    // endregion
}
