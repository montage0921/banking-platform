package com.group1.banking.util;

/**
 * The one place account identifiers and account numbers are derived.
 *
 * <p>Extracted so {@code AccountService} and the persona seeder cannot drift apart.
 * {@code Account.accountId} carries no {@code @GeneratedValue} - it is assigned by hand - so a
 * seeder computing identifiers its own way would collide the moment {@code AccountService}
 * next created an account and landed on a value the seed had already taken.
 *
 * <p>ponytail: the count-based scheme reuses an identifier after any account is deleted, since
 * the count drops. That is pre-existing behaviour, not something the seeder introduced, and
 * repairing it means adding a real identity strategy to the {@code account} table and touching
 * every account-creating path - a change of its own, deliberately out of scope here. Both
 * callers now share this method, so a future fix lands in one place and both inherit it.
 */
public final class AccountIdentifiers {

    private AccountIdentifiers() {
    }

    /**
     * @param existingAccountCount current row count of the account table
     */
    public static long nextAccountId(long existingAccountCount) {
        return existingAccountCount + 1000;
    }

    public static String accountNumber(long accountId) {
        return String.format("ACC%010d", accountId);
    }
}
