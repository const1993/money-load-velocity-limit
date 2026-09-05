package com.example.moneyload.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

public final class VelocityBuckets {
    private VelocityBuckets() {
    }

    public static LocalDate daily(Instant eventTimestamp) {
        return eventTimestamp.atOffset(ZoneOffset.UTC).toLocalDate();
    }

    public static LocalDate weekly(Instant eventTimestamp) {
        return daily(eventTimestamp).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
