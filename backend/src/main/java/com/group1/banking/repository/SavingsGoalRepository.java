package com.group1.banking.repository;

import com.group1.banking.entity.SavingsGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Savings Goal Repository
 * Data access layer for SavingsGoal entity
 * 
 * Provides custom queries for:
 * - Finding active goals by customer
 * - Finding goal by customer + account
 * - Fetching goal with account balance (LEFT JOIN account)
 */
@Repository
public interface SavingsGoalRepository extends JpaRepository<SavingsGoal, Long> {
    
    /**
     * Find single active goal for a customer
     * @param customerId Customer ID
     * @return Optional containing SavingsGoal if found, empty otherwise
     */
    @Query("SELECT sg FROM SavingsGoal sg WHERE sg.customerId = :customerId AND sg.deletedAt IS NULL")
    Optional<SavingsGoal> findActiveByCustomerId(@Param("customerId") Long customerId);
    
    /**
     * Find all active goals for a customer
     * @param customerId Customer ID
     * @return List of active SavingsGoals
     */
    @Query("SELECT sg FROM SavingsGoal sg WHERE sg.customerId = :customerId AND sg.deletedAt IS NULL")
    List<SavingsGoal> findAllActiveByCustomerId(@Param("customerId") Long customerId);
    
    /**
     * Find active goal for a specific account
     * @param customerId Customer ID (ownership check)
     * @param accountId Account ID
     * @return Optional containing SavingsGoal if found, empty otherwise
     */
    @Query("SELECT sg FROM SavingsGoal sg WHERE sg.customerId = :customerId AND sg.account.accountId = :accountId AND sg.deletedAt IS NULL")
    Optional<SavingsGoal> findActiveByCustomerIdAndAccountId(
            @Param("customerId") Long customerId,
            @Param("accountId") Long accountId
    );
    
    /**
     * Find goal by ID with ownership check
     * @param goalId Goal ID
     * @param customerId Customer ID (ownership check)
     * @return Optional containing SavingsGoal if found, empty otherwise
     */
    @Query("SELECT sg FROM SavingsGoal sg WHERE sg.goalId = :goalId AND sg.customerId = :customerId AND sg.deletedAt IS NULL")
    Optional<SavingsGoal> findByGoalIdAndCustomerId(
            @Param("goalId") Long goalId,
            @Param("customerId") Long customerId
    );

    /** Goals on one account, used by persona reset: savings goals are owned by Account
     *  without a cascading collection, so they must be deleted explicitly. */
    List<SavingsGoal> findAllByAccountAccountId(Long accountId);
}
