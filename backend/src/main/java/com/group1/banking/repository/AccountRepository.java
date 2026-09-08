package com.group1.banking.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.group1.banking.entity.Account;
import com.group1.banking.entity.AccountStatus;
import com.group1.banking.entity.AccountType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountIdAndDeletedAtIsNull(Long accountId);

    List<Account> findAllByCustomerCustomerIdAndDeletedAtIsNullAndStatus(Long customerId, AccountStatus status);

    List<Account> findAllByCustomerCustomerIdAndDeletedAtIsNullAndStatusNot(Long customerId, AccountStatus status);

    List<Account> findAllByDeletedAtIsNull();

    /** Every account of a customer, deleted or not. Used by persona reset, which must
     *  remove soft-deleted rows too - a persona left with a tombstoned account would not
     *  match its catalogue entry. */
    List<Account> findAllByCustomerCustomerId(Long customerId);

    /** Highest account id strictly below {@code exclusiveUpperBound}, or 0 when there is none.
     *  Used to allocate a run of new ids without re-reading a row count that has not been
     *  flushed yet, while staying out of an id band another tool owns. */
    @Query("select coalesce(max(a.accountId), 0) from Account a where a.accountId < :exclusiveUpperBound")
    long findMaxAccountIdBelow(@Param("exclusiveUpperBound") long exclusiveUpperBound);

    boolean existsByCustomerCustomerIdAndDeletedAtIsNullAndStatus(Long customerId, AccountStatus status);

    Optional<Account> findByAccountId(Long accountNumber);

    boolean existsByCustomerCustomerIdAndAccountTypeAndDeletedAtIsNull(Long customerId, AccountType accountType);

}
