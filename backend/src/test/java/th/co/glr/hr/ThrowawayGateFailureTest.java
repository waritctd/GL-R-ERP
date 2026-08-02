package th.co.glr.hr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * THROWAWAY. Deliberately failing test used once to prove that the issue #435 gate can actually go
 * red on a backend PR. Lives only on the tmp/verify-435-gate branch and is never merged.
 */
class ThrowawayGateFailureTest {

  @Test
  void deliberatelyFails() {
    assertEquals(1, 2, "deliberate failure — proving build-and-test reports red");
  }
}
