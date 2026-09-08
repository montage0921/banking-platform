package com.group1.banking.seed;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.group1.banking.seed.PersonaCatalogue.SeedTransaction;

/**
 * Resolves the catalogue's relative offsets into real timestamps against a single moment
 * captured once per seed or reset run.
 *
 * <p>Capturing the anchor once, rather than calling {@code Instant.now()} per row, is the
 * requirement rather than an optimisation: a run that spans midnight would otherwise produce a
 * persona whose transactions straddle two different "todays", making the run unreproducible in
 * exactly the way the specification forbids.
 *
 * <p>Relative anchoring exists because both consumers move their own windows continuously -
 * {@code RiskScoreService} recomputes {@code now - minMonths} on every call, and
 * {@code SavingsChatContextService} recomputes {@code now - lookbackDays} on every call. Fixed
 * calendar dates would be overtaken by both, silently turning the salaried persona into an
 * insufficient-data one and pushing the sparse persona's transactions out of the chatbot's
 * lookback window.
 *
 * <p>The cost, accepted deliberately: literal timestamps differ between environments seeded on
 * different days. Tests must assert on outcomes and relative positions, never on dates.
 */
public final class PersonaDateAnchor {

    private final Instant anchor;

    private PersonaDateAnchor(Instant anchor) {
        this.anchor = anchor;
    }

    /** Captures the anchor. Call once per run and pass the result down. */
    public static PersonaDateAnchor now() {
        return new PersonaDateAnchor(Instant.now());
    }

    /** Fixed anchor, for tests that need to reason about a specific moment. */
    public static PersonaDateAnchor at(Instant fixed) {
        return new PersonaDateAnchor(fixed);
    }

    public Instant anchor() {
        return anchor;
    }

    public Instant daysAgo(int days) {
        return anchor.minus(days, ChronoUnit.DAYS);
    }

    public LocalDate daysAhead(int days) {
        return LocalDate.ofInstant(anchor, ZoneOffset.UTC).plusDays(days);
    }

    /**
     * Expands one catalogue entry into its monthly series, so eighteen months of salary is a
     * few lines of YAML rather than eighteen.
     *
     * <p>The first occurrence sits at {@code daysAgo}; each subsequent one steps back a further
     * month from there. Months are stepped with {@link LocalDate#minusMonths} rather than a flat
     * 30-day subtraction so the series lands on comparable days of the month and does not drift
     * against the calendar over a long history.
     *
     * @return one timestamp per occurrence, newest first
     */
    public List<Instant> expand(SeedTransaction transaction) {
        int occurrences = Math.max(1, transaction.getRepeatMonthly());
        Instant first = daysAgo(transaction.getDaysAgo());
        LocalDate firstDate = LocalDate.ofInstant(first, ZoneOffset.UTC);

        List<Instant> timestamps = new ArrayList<>(occurrences);
        for (int i = 0; i < occurrences; i++) {
            LocalDate date = firstDate.minusMonths(i);
            // Preserve the time-of-day of the anchor so a series does not all land at midnight,
            // which would look unlike real activity in a demo.
            Instant occurrence = date.atStartOfDay(ZoneOffset.UTC).toInstant()
                    .plus(anchor.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay(), ChronoUnit.SECONDS);
            timestamps.add(occurrence);
        }
        return timestamps;
    }

    /** Age in whole days of the oldest timestamp in a series; used by margin assertions. */
    public long daysSince(Instant earlier) {
        return ChronoUnit.DAYS.between(earlier, anchor);
    }
}
