package com.group1.banking.util;

import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;

/**
 * Canadian statutory holiday service. (T010)
 * Provides holiday detection and business day calculation.
 * Hard-coded federal statutory holidays (non-year-specific rules).
 */
@Service
public class CanadianHolidayService {

    /**
     * Returns true if the date falls on a Canadian federal statutory holiday.
     * Hard-coded rules for annual recurring holidays.
     */
    public boolean isHoliday(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        DayOfWeek dow = date.getDayOfWeek();

        // New Year's Day — January 1
        if (month == 1 && day == 1) return true;

        // Good Friday — 2 days before Easter (computed dynamically)
        LocalDate goodFriday = computeGoodFriday(date.getYear());
        if (date.equals(goodFriday)) return true;

        // Victoria Day — last Monday before May 25
        if (month == 5 && dow == DayOfWeek.MONDAY && day >= 18 && day <= 24) return true;

        // Canada Day — July 1
        if (month == 7 && day == 1) return true;

        // Civic Holiday — first Monday in August
        if (month == 8 && dow == DayOfWeek.MONDAY && day <= 7) return true;

        // Labour Day — first Monday in September
        if (month == 9 && dow == DayOfWeek.MONDAY && day <= 7) return true;

        // Thanksgiving — second Monday in October
        if (month == 10 && dow == DayOfWeek.MONDAY && day >= 8 && day <= 14) return true;

        // Remembrance Day — November 11
        if (month == 11 && day == 11) return true;

        // Christmas Day — December 25
        if (month == 12 && day == 25) return true;

        // Boxing Day — December 26
        if (month == 12 && day == 26) return true;

        return false;
    }

    /**
     * Returns true if the date falls on a weekend.
     */
    public boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * Returns the next business day at or after the given datetime.
     * Skips weekends and statutory holidays.
     */
    public LocalDateTime nextBusinessDay(LocalDateTime from) {
        LocalDate candidate = from.toLocalDate();
        while (isWeekend(candidate) || isHoliday(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.atStartOfDay();
    }

    /**
     * Computes Good Friday for the given year using the Anonymous Gregorian algorithm.
     */
    private LocalDate computeGoodFriday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        LocalDate easter = LocalDate.of(year, month, day);
        return easter.minusDays(2); // Good Friday is 2 days before Easter
    }
}
