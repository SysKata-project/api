package org.syskata.api.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PracticeDaysTest {
  private com.fasterxml.jackson.databind.node.ObjectNode progress(
      String now, String zone, List<PracticeDays.Change> changes, String... events) {
    var times = new TreeSet<Instant>();
    for (String event : events) times.add(Instant.parse(event));
    var result = JsonData.object();
    new PracticeDays(Instant.parse(now), zone, changes, times).append(result);
    return result;
  }

  @Test
  void midnightMissedDaysAndHistoricalLongest() {
    var first =
        progress("2026-11-02T06:59:59Z", "America/Denver", List.of(), "2026-11-02T06:59:58Z");
    assertEquals("2026-11-01", first.path("local_date").asText());
    assertEquals(1, first.path("current_streak").asInt());
    var boundary =
        progress("2026-11-02T07:00:00Z", "America/Denver", List.of(), "2026-11-02T06:59:58Z");
    assertFalse(boundary.path("daily_goal_completed").asBoolean());
    assertEquals(1, boundary.path("current_streak").asInt());
    var missed =
        progress("2026-11-03T07:00:00Z", "America/Denver", List.of(), "2026-11-02T06:59:58Z");
    assertEquals(0, missed.path("current_streak").asInt());
    assertEquals(1, missed.path("longest_streak").asInt());
    var restarted =
        progress(
            "2026-11-04T08:00:00Z",
            "America/Denver",
            List.of(),
            "2026-11-01T12:00:00Z",
            "2026-11-02T12:00:00Z",
            "2026-11-04T08:00:00Z");
    assertEquals(1, restarted.path("current_streak").asInt());
    assertEquals(2, restarted.path("longest_streak").asInt());
  }

  @Test
  void daylightSavingDaysHaveCalendarBoundaries() {
    assertEquals(
        Instant.parse("2026-03-09T06:00:00Z"),
        PracticeDays.nextMidnight(Instant.parse("2026-03-08T07:00:00Z"), "America/Denver"));
    assertEquals(
        Instant.parse("2026-11-02T07:00:00Z"),
        PracticeDays.nextMidnight(Instant.parse("2026-11-01T06:00:00Z"), "America/Denver"));
    assertEquals(
        0, progress("2026-11-01T06:00:00Z", "UTC", List.of()).path("current_streak").asInt());
  }

  @Test
  void westwardRepeatedDatePreservesStreakAndDoesNotPrecreditFullDay() {
    var change =
        new PracticeDays.Change(
            "UTC",
            "America/Denver",
            Instant.parse("2026-11-01T00:00:00Z"),
            Instant.parse("2026-11-02T00:00:00Z"),
            Instant.parse("2026-11-02T07:00:00Z"));
    var pending = progress("2026-11-01T22:00:00Z", "UTC", List.of(change), "2026-11-01T12:00:00Z");
    assertEquals(
        "America/Denver", pending.path("pending_timezone_change").path("timezone").asText());
    var partial =
        progress(
            "2026-11-02T03:00:00Z",
            "UTC",
            List.of(change),
            "2026-11-01T12:00:00Z",
            "2026-11-02T02:00:00Z");
    assertTrue(partial.path("transition_day").asBoolean());
    assertTrue(partial.path("daily_goal_completed").asBoolean());
    assertEquals(1, partial.path("current_streak").asInt());
    var full =
        progress(
            "2026-11-02T08:00:00Z",
            "UTC",
            List.of(change),
            "2026-11-01T12:00:00Z",
            "2026-11-02T02:00:00Z");
    assertFalse(full.path("daily_goal_completed").asBoolean());
    assertEquals(1, full.path("current_streak").asInt());
    var next =
        progress(
            "2026-11-02T09:00:00Z",
            "UTC",
            List.of(change),
            "2026-11-01T12:00:00Z",
            "2026-11-02T02:00:00Z",
            "2026-11-02T09:00:00Z");
    assertEquals(2, next.path("current_streak").asInt());
  }

  @Test
  void eastwardSkippedDateAndSeveralChangesUseInstants() {
    var east =
        new PracticeDays.Change(
            "Pacific/Honolulu",
            "Pacific/Kiritimati",
            Instant.parse("2026-11-01T10:00:00Z"),
            Instant.parse("2026-11-02T10:00:00Z"),
            Instant.parse("2026-11-02T10:00:00Z"));
    var result =
        progress(
            "2026-11-02T11:00:00Z",
            "Pacific/Honolulu",
            List.of(east),
            "2026-11-01T12:00:00Z",
            "2026-11-02T11:00:00Z");
    assertEquals("2026-11-03", result.path("local_date").asText());
    assertEquals(2, result.path("current_streak").asInt());
    assertFalse(result.path("transition_day").asBoolean());
  }
}
