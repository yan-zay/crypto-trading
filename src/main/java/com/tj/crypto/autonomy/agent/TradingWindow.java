package com.tj.crypto.autonomy.agent;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** 版本化政策中的周期交易时段；支持跨午夜且要求整个 proposal TTL 位于同一窗口。 */
public record TradingWindow(
        DayOfWeek startDay,
        LocalTime startInclusive,
        LocalTime endExclusive,
        ZoneId zoneId) {

    public TradingWindow {
        if (startDay == null || startInclusive == null || endExclusive == null || zoneId == null) {
            throw new IllegalArgumentException("complete trading window is required");
        }
        if (startInclusive.equals(endExclusive)) {
            throw new IllegalArgumentException("24-hour implicit windows are forbidden; declare explicit windows");
        }
    }

    public boolean containsInterval(Instant intervalStart, Instant intervalEnd) {
        if (intervalStart == null || intervalEnd == null || !intervalEnd.isAfter(intervalStart)) return false;
        ZonedDateTime localStart = intervalStart.atZone(zoneId);
        boolean overnight = startInclusive.isAfter(endExclusive);
        LocalDate occurrenceDate = null;
        if (!overnight) {
            if (localStart.getDayOfWeek() == startDay
                    && !localStart.toLocalTime().isBefore(startInclusive)
                    && localStart.toLocalTime().isBefore(endExclusive)) {
                occurrenceDate = localStart.toLocalDate();
            }
        } else if (localStart.getDayOfWeek() == startDay
                && !localStart.toLocalTime().isBefore(startInclusive)) {
            occurrenceDate = localStart.toLocalDate();
        } else if (localStart.getDayOfWeek() == startDay.plus(1)
                && localStart.toLocalTime().isBefore(endExclusive)) {
            occurrenceDate = localStart.toLocalDate().minusDays(1);
        }
        if (occurrenceDate == null) return false;
        Instant allowedStart = occurrenceDate.atTime(startInclusive).atZone(zoneId).toInstant();
        LocalDate endDate = overnight ? occurrenceDate.plusDays(1) : occurrenceDate;
        Instant allowedEnd = endDate.atTime(endExclusive).atZone(zoneId).toInstant();
        return !intervalStart.isBefore(allowedStart) && !intervalEnd.isAfter(allowedEnd);
    }
}
