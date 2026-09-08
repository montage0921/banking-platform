package com.group1.banking.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.group1.banking.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findByAccount_Customer_CustomerIdAndTimestampBetweenOrderByTimestampAsc(
            Long customerId, Instant start, Instant end);

    /**
     * Timestamp of the customer's first transaction, or null when they have none.
     * Used to measure how far back their history reaches.
     */
    @Query("select min(t.timestamp) from Transaction t where t.account.customer.customerId = :customerId")
    Instant findEarliestTimestampForCustomer(@Param("customerId") Long customerId);

    /** Transactions on one account, used by persona reset: transactions are owned by
     *  Account without a cascading collection, so they must be deleted explicitly. */
    List<Transaction> findAllByAccountAccountId(Long accountId);
}
