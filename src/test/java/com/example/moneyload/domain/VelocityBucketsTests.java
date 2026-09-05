package com.example.moneyload.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityBucketsTests {
    @ParameterizedTest
    @CsvSource({
            "2018-01-01T00:00:00Z,2018-01-01,2018-01-01",
            "2018-01-01T23:59:59.999999999Z,2018-01-01,2018-01-01",
            "2018-01-02T00:00:00Z,2018-01-02,2018-01-01",
            "2018-01-07T23:59:59.999999999Z,2018-01-07,2018-01-01",
            "2018-01-08T00:00:00Z,2018-01-08,2018-01-08",
            "2021-01-01T00:00:00Z,2021-01-01,2020-12-28"})
    void usesUtcCalendarBoundaries(String event, LocalDate day, LocalDate week) {
        assertThat(VelocityBuckets.daily(Instant.parse(event))).isEqualTo(day);
        assertThat(VelocityBuckets.weekly(Instant.parse(event))).isEqualTo(week);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-07-10T14:00:00+02:00,2026-07-10T12:00:00Z,2026-07-10,2026-07-06",
            "2026-01-10T13:00:00+01:00,2026-01-10T12:00:00Z,2026-01-10,2026-01-05",
            "2026-03-29T01:59:59+01:00,2026-03-29T00:59:59Z,2026-03-29,2026-03-23",
            "2026-03-29T03:00:00+02:00,2026-03-29T01:00:00Z,2026-03-29,2026-03-23",
            "2026-10-25T02:59:59+02:00,2026-10-25T00:59:59Z,2026-10-25,2026-10-19",
            "2026-10-25T02:00:00+01:00,2026-10-25T01:00:00Z,2026-10-25,2026-10-19",
            "2018-01-08T01:59:59.999999999+02:00,2018-01-07T23:59:59.999999999Z,2018-01-07,2018-01-01",
            "2018-01-07T19:00:00-05:00,2018-01-08T00:00:00Z,2018-01-08,2018-01-08"})
    void offsetsAndDstDoNotChangeBuckets(String offset, String utc, LocalDate day, LocalDate week) {
        var event = OffsetDateTime.parse(offset).toInstant();
        var equivalent = Instant.parse(utc);
        assertThat(event).isEqualTo(equivalent);
        assertThat(VelocityBuckets.daily(event)).isEqualTo(VelocityBuckets.daily(equivalent)).isEqualTo(day);
        assertThat(VelocityBuckets.weekly(event)).isEqualTo(VelocityBuckets.weekly(equivalent)).isEqualTo(week);
    }
}
