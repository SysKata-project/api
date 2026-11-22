package org.syskata.api.learning;

final class ReviewPolicy {
  private ReviewPolicy() {}

  /** Zero means retain the schedule (or remain unscheduled before any success). */
  static int interval(
      int current, boolean correct, boolean assisted, boolean completed, boolean originalDue) {
    if (current == 0) return correct ? 1 : 0;
    if (!correct || assisted) return 1;
    if (!completed) return 0;
    if (!originalDue) return 1;
    return current == 1 ? 7 : 15;
  }
}
