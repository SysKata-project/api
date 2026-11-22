package org.syskata.api.learning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;

/** Calendar periods are ordered by instant, never by potentially repeated date labels. */
final class PracticeDays {
  record Change(
      String oldZone, String newZone, Instant outgoing, Instant effective, Instant full) {}

  private final Instant now;
  private final String initialZone;
  private final List<Change> changes;
  private final NavigableSet<Instant> completions;

  PracticeDays(
      Instant now, String initialZone, List<Change> changes, NavigableSet<Instant> completions) {
    this.now = now;
    this.initialZone = initialZone;
    this.changes = changes;
    this.completions = completions;
  }

  static PracticeDays load(Connection c, UUID learner, Instant now) throws SQLException {
    String zone;
    try (var q = LabSql.query(c, "SELECT timezone FROM learners WHERE id=?", learner);
        var r = q.executeQuery()) {
      r.next();
      zone = r.getString(1);
    }
    List<Change> changes = new ArrayList<>();
    try (var q =
            LabSql.query(
                c,
                "SELECT old_timezone,new_timezone,outgoing_start,effective_at,full_day_start FROM timezone_changes WHERE learner_id=? ORDER BY effective_at",
                learner);
        var r = q.executeQuery()) {
      while (r.next())
        changes.add(
            new Change(
                r.getString(1),
                r.getString(2),
                r.getTimestamp(3).toInstant(),
                r.getTimestamp(4).toInstant(),
                r.getTimestamp(5).toInstant()));
    }
    NavigableSet<Instant> completions = new TreeSet<>();
    try (var q =
            LabSql.query(
                c,
                "SELECT confirmed_at FROM lesson_completion_events WHERE learner_id=? AND confirmed_at<=?::timestamptz ORDER BY confirmed_at",
                learner,
                now.toString());
        var r = q.executeQuery()) {
      while (r.next()) completions.add(r.getTimestamp(1).toInstant());
    }
    PracticeDays result =
        new PracticeDays(
            now, changes.isEmpty() ? zone : changes.getFirst().oldZone(), changes, completions);
    if (!zone.equals(result.zone()))
      LabSql.update(c, "UPDATE learners SET timezone=? WHERE id=?", result.zone(), learner);
    return result;
  }

  String zone() {
    return zoneAt(now);
  }

  String date() {
    return now.atZone(ZoneId.of(zone())).toLocalDate().toString();
  }

  private String zoneAt(Instant instant) {
    String zone = initialZone;
    for (Change change : changes) {
      if (instant.isBefore(change.effective())) break;
      zone = change.newZone();
    }
    return zone;
  }

  private Change partial(Instant instant) {
    for (Change change : changes)
      if (!instant.isBefore(change.effective()) && instant.isBefore(change.full())) return change;
    return null;
  }

  static Instant midnight(Instant instant, String zone) {
    return instant.atZone(ZoneId.of(zone)).toLocalDate().atStartOfDay(ZoneId.of(zone)).toInstant();
  }

  static Instant nextMidnight(Instant instant, String zone) {
    return instant
        .atZone(ZoneId.of(zone))
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(ZoneId.of(zone))
        .toInstant();
  }

  Instant guardStart() {
    Change transition = partial(now);
    return transition == null ? midnight(now, zone()) : transition.outgoing();
  }

  Instant dayStart(Instant instant) {
    Change transition = partial(instant);
    return transition == null ? midnight(instant, zoneAt(instant)) : transition.effective();
  }

  private Instant dayEnd(Instant start) {
    Change transition = partial(start);
    if (transition != null) return transition.full();
    Instant end = nextMidnight(start, zoneAt(start));
    for (Change change : changes)
      if (change.effective().isAfter(start) && change.effective().isBefore(end))
        return change.effective();
    return end;
  }

  private boolean completed(Instant start, Instant end) {
    Instant event = completions.ceiling(start);
    return event != null && event.isBefore(end);
  }

  void append(ObjectNode result) {
    result.put("timezone", zone());
    result.put("local_date", date());
    result.put("transition_day", partial(now) != null);
    result.put("daily_goal_completed", completed(dayStart(now), dayEnd(dayStart(now))));
    result.putNull("pending_timezone_change");
    for (Change change : changes) {
      if (change.effective().isAfter(now)) {
        var pending = result.putObject("pending_timezone_change");
        pending.put("timezone", change.newZone());
        pending.put("effective_at", change.effective().toString());
        break;
      }
    }
    int current = 0;
    int longest = 0;
    if (!completions.isEmpty()) {
      Instant start = dayStart(completions.first());
      while (!start.isAfter(now)) {
        Instant end = dayEnd(start);
        if (partial(start) == null) {
          if (completed(start, end)) current++;
          else if (!end.isAfter(now)) current = 0;
          longest = Math.max(longest, current);
        }
        start = end;
      }
    }
    result.put("current_streak", current);
    result.put("longest_streak", longest);
  }
}
