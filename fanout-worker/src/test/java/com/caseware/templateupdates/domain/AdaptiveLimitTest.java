package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdaptiveLimitTest {

    private final AdaptiveLimit limit = new AdaptiveLimit();

    @Test
    void startsAtTheConfiguredLimit() {
        assertThat(limit.limit(50)).isEqualTo(50);
        assertThat(limit.generation()).isZero();
    }

    @Test
    void overloadHalvesTheLimitAndOpensANewGeneration() {
        assertThat(limit.onOverloaded(0, 50)).isTrue();
        assertThat(limit.limit(50)).isEqualTo(25);
        assertThat(limit.generation()).isEqualTo(1);
    }

    @Test
    void overloadsFromTheSameGenerationLowerTheLimitOnlyOnce() {
        limit.onOverloaded(0, 50);
        for (int i = 0; i < 49; i++) {
            assertThat(limit.onOverloaded(0, 50)).isFalse();
        }
        assertThat(limit.limit(50)).isEqualTo(25);
        assertThat(limit.generation()).isEqualTo(1);
    }

    @Test
    void overloadFromTheCurrentGenerationLowersAgain() {
        limit.onOverloaded(0, 50);
        assertThat(limit.onOverloaded(1, 50)).isTrue();
        assertThat(limit.limit(50)).isEqualTo(12);
        assertThat(limit.generation()).isEqualTo(2);
    }

    @Test
    void limitNeverDropsBelowOne() {
        limit.onOverloaded(0, 1);
        assertThat(limit.limit(1)).isEqualTo(1);
        limit.onOverloaded(1, 1);
        assertThat(limit.limit(50)).isEqualTo(1);
        assertThat(limit.limit(1)).isEqualTo(1);
    }

    @Test
    void oddLimitsRoundDown() {
        limit.onOverloaded(0, 3);
        assertThat(limit.limit(3)).isEqualTo(1);
    }

    @Test
    void normalResponsesRaiseTheLimitByOneEveryEffectiveLimitResponses() {
        limit.onOverloaded(0, 50);
        for (int i = 0; i < 24; i++) {
            limit.onNormal(50);
        }
        assertThat(limit.limit(50)).isEqualTo(25);
        limit.onNormal(50);
        assertThat(limit.limit(50)).isEqualTo(26);
        for (int i = 0; i < 25; i++) {
            limit.onNormal(50);
        }
        assertThat(limit.limit(50)).isEqualTo(26);
        limit.onNormal(50);
        assertThat(limit.limit(50)).isEqualTo(27);
    }

    @Test
    void limitNeverRisesAboveTheConfiguredOne() {
        limit.onOverloaded(0, 2);
        for (int i = 0; i < 100; i++) {
            limit.onNormal(2);
        }
        assertThat(limit.limit(2)).isEqualTo(2);
    }

    @Test
    void successesStartOverAfterEachDrop() {
        limit.onOverloaded(0, 50);
        for (int i = 0; i < 20; i++) {
            limit.onNormal(50);
        }
        limit.onOverloaded(1, 50);
        assertThat(limit.limit(50)).isEqualTo(12);
        for (int i = 0; i < 11; i++) {
            limit.onNormal(50);
        }
        assertThat(limit.limit(50)).isEqualTo(12);
        limit.onNormal(50);
        assertThat(limit.limit(50)).isEqualTo(13);
    }

    @Test
    void whenNothingWasLoweredNormalResponsesChangeNothing() {
        for (int i = 0; i < 200; i++) {
            limit.onNormal(50);
        }
        assertThat(limit.limit(50)).isEqualTo(50);
    }

    @Test
    void raisingTheConfiguredLimitIsFollowedImmediatelyWhenNothingWasLowered() {
        assertThat(limit.limit(80)).isEqualTo(80);
    }

    @Test
    void loweringIsComputedFromTheConfiguredLimitWhenItIsBelowTheEffectiveOne() {
        limit.onOverloaded(0, 20);
        assertThat(limit.limit(20)).isEqualTo(10);
    }

    @Test
    void onceBackAtTheConfiguredLimitFurtherNormalResponsesDoNotInflateTheEffectiveOne() {
        limit.onOverloaded(0, 4);
        for (int i = 0; i < 2 + 3; i++) {
            limit.onNormal(4);
        }
        assertThat(limit.limit(4)).isEqualTo(4);
        for (int i = 0; i < 20; i++) {
            limit.onNormal(4);
        }
        assertThat(limit.limit(8)).isEqualTo(4);
    }
}
