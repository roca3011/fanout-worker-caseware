package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2));

    @Test
    void noMoreAttemptsMeansNoRetry() {
        var policy = new RetryPolicy(new Random(1));
        assertThat(policy.nextDelay(3, 3, DELAYS)).isEmpty();
        assertThat(policy.nextDelay(4, 3, DELAYS)).isEmpty();
        assertThat(policy.nextDelay(1, 1, List.of())).isEmpty();
    }

    @Test
    void firstRetryWaitsAroundThirtySecondsWithinTwentyPercent() {
        var policy = new RetryPolicy(new Random(42));
        for (int i = 0; i < 200; i++) {
            Optional<Duration> delay = policy.nextDelay(1, 3, DELAYS);
            assertThat(delay).isPresent();
            assertThat(delay.get()).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
        }
    }

    @Test
    void secondRetryWaitsAroundTwoMinutesWithinTwentyPercent() {
        var policy = new RetryPolicy(new Random(7));
        for (int i = 0; i < 200; i++) {
            assertThat(policy.nextDelay(2, 3, DELAYS).orElseThrow())
                    .isBetween(Duration.ofSeconds(96), Duration.ofSeconds(144));
        }
    }

    @Test
    void jitterSpansTheWholeRangeAndIsExactAtTheExtremes() {
        var low = new RetryPolicy(new FixedRandom(0.0));
        var high = new RetryPolicy(new FixedRandom(1.0));
        var mid = new RetryPolicy(new FixedRandom(0.5));
        assertThat(low.nextDelay(1, 3, DELAYS).orElseThrow()).isEqualTo(Duration.ofSeconds(24));
        assertThat(high.nextDelay(1, 3, DELAYS).orElseThrow()).isEqualTo(Duration.ofSeconds(36));
        assertThat(mid.nextDelay(1, 3, DELAYS).orElseThrow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(low.nextDelay(2, 3, DELAYS).orElseThrow()).isEqualTo(Duration.ofSeconds(96));
    }

    @Test
    void sameSeedGivesSameDelays() {
        var a = new RetryPolicy(new Random(5));
        var b = new RetryPolicy(new Random(5));
        assertThat(a.nextDelay(1, 3, DELAYS)).isEqualTo(b.nextDelay(1, 3, DELAYS));
    }

    private static final class FixedRandom implements RandomGenerator {
        private final double value;

        FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }
}
