package org.syskata.api.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ReviewPolicyTest {
  @Test
  void evidenceAdvancesOnlyOnceForAnUnchangedDueReview() {
    assertEquals(0, ReviewPolicy.interval(0, false, false, false, false));
    assertEquals(1, ReviewPolicy.interval(0, true, false, false, false));
    assertEquals(1, ReviewPolicy.interval(15, false, false, false, false));
    assertEquals(1, ReviewPolicy.interval(15, true, true, true, true));
    assertEquals(0, ReviewPolicy.interval(7, true, false, false, true));
    assertEquals(1, ReviewPolicy.interval(7, true, false, true, false));
    assertEquals(7, ReviewPolicy.interval(1, true, false, true, true));
    assertEquals(15, ReviewPolicy.interval(7, true, false, true, true));
    assertEquals(15, ReviewPolicy.interval(15, true, false, true, true));
  }
}
