package com.group1.banking.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.group1.banking.entity.TransactionDirection;
import com.group1.banking.entity.TransactionStatus;
import com.group1.banking.seed.PersonaCatalogue.SeedTransaction;

class PersonaDateAnchorTest {

    /** A moment just before midnight - the case a per-row Instant.now() would get wrong. */
    private static final Instant LATE_NIGHT =
            LocalDate.of(2026, 3, 15).atTime(23, 59, 30).toInstant(ZoneOffset.UTC);

    @Test
    @DisplayName("offsets resolve against the anchor, not against the wall clock")
    void offsetsUseTheAnchor() {
        PersonaDateAnchor anchor = PersonaDateAnchor.at(LATE_NIGHT);

        assertThat(anchor.daysAgo(10)).isEqualTo(LATE_NIGHT.minus(10, ChronoUnit.DAYS));
        assertThat(anchor.daysAhead(90))
                .isEqualTo(LocalDate.of(2026, 3, 15).plusDays(90));
    }

    @Test
    @DisplayName("a run spanning midnight still resolves every offset consistently")
    void oneAnchorSurvivesMidnight() {
        PersonaDateAnchor anchor = PersonaDateAnchor.at(LATE_NIGHT);

        // Two offsets resolved at different real moments during a slow seed run must still be
        // exactly the intended distance apart. Per-row Instant.now() would skew one by a day.
        Instant tenDaysAgo = anchor.daysAgo(10);
        Instant elevenDaysAgo = anchor.daysAgo(11);

        assertThat(ChronoUnit.DAYS.between(elevenDaysAgo, tenDaysAgo)).isEqualTo(1);
        assertThat(ChronoUnit.DAYS.between(tenDaysAgo, anchor.anchor())).isEqualTo(10);
    }

    @Test
    @DisplayName("repeatMonthly expands to the right number of occurrences")
    void expansionCount() {
        PersonaDateAnchor anchor = PersonaDateAnchor.at(LATE_NIGHT);

        assertThat(anchor.expand(monthly(1, 1))).hasSize(1);
        assertThat(anchor.expand(monthly(1, 18))).hasSize(18);
        // A missing or zero value must still produce the single occurrence, not none.
        assertThat(anchor.expand(monthly(1, 0))).hasSize(1);
    }

    @Test
    @DisplayName("occurrences step back a calendar month at a time, without drifting")
    void expansionStepsByCalendarMonth() {
        PersonaDateAnchor anchor = PersonaDateAnchor.at(LATE_NIGHT);

        List<Instant> occurrences = anchor.expand(monthly(1, 18));

        // Newest first, and each roughly a month older than the last.
        for (int i = 1; i < occurrences.size(); i++) {
            long gap = ChronoUnit.DAYS.between(occurrences.get(i), occurrences.get(i - 1));
            assertThat(gap)
                    .as("occurrence %d should sit about a month after occurrence %d", i - 1, i)
                    .isBetween(28L, 31L);
        }

        // 18 monthly steps must comfortably exceed the 3-month risk window; a flat 30-day
        // subtraction would drift far enough over 18 months to matter.
        long spanDays = ChronoUnit.DAYS.between(
                occurrences.get(occurrences.size() - 1), occurrences.get(0));
        assertThat(spanDays).isBetween(510L, 525L);
    }

    @Test
    @DisplayName("expanded occurrences do not all land at midnight")
    void expansionPreservesTimeOfDay() {
        PersonaDateAnchor anchor = PersonaDateAnchor.at(LATE_NIGHT);

        List<Instant> occurrences = anchor.expand(monthly(1, 3));

        assertThat(occurrences)
                .as("a demo full of midnight transactions looks unlike real activity")
                .allSatisfy(t -> assertThat(t.atZone(ZoneOffset.UTC).toLocalTime().toSecondOfDay())
                        .isGreaterThan(0));
    }

    @Test
    @DisplayName("daysSince measures age against the anchor")
    void daysSince() {
        PersonaDateAnchor anchor = PersonaDateAnchor.at(LATE_NIGHT);

        assertThat(anchor.daysSince(anchor.daysAgo(45))).isEqualTo(45);
    }

    private SeedTransaction monthly(int daysAgo, int repeatMonthly) {
        SeedTransaction t = new SeedTransaction();
        t.setAccountRef("main");
        t.setAmount(new BigDecimal("100.00"));
        t.setDirection(TransactionDirection.CREDIT);
        t.setStatus(TransactionStatus.SUCCESS);
        t.setDaysAgo(daysAgo);
        t.setRepeatMonthly(repeatMonthly);
        t.setCategory("Salary");
        t.setDescription("test");
        return t;
    }
}
