package com.caseware.templateupdates.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CapacitySplitTest {

    private static final double SHARE = 0.80;

    private static int[] fillWithBothWaiting(int limit) {
        int urgent = 0;
        int initial = 0;
        for (int slot = 0; slot < limit; slot++) {
            Optional<Priority> next = CapacitySplit.next(limit, SHARE, urgent, initial, true, true);
            if (next.orElseThrow() == Priority.URGENT) {
                urgent++;
            } else {
                initial++;
            }
        }
        return new int[] {urgent, initial};
    }

    @ParameterizedTest
    @CsvSource({"50,40,10", "4,3,1", "3,2,1", "2,2,0", "1,1,0"})
    void withBothWaitingTheLimitIsSplitByShareKeepingAtLeastOneUrgent(int limit, int urgent, int initial) {
        assertThat(fillWithBothWaiting(limit)).containsExactly(urgent, initial);
    }

    @Test
    void aSingleTypeUsesTheWholeLimit() {
        assertThat(CapacitySplit.next(50, SHARE, 0, 49, false, true)).contains(Priority.INITIAL_LOAD);
        assertThat(CapacitySplit.next(50, SHARE, 49, 0, true, false)).contains(Priority.URGENT);
        assertThat(CapacitySplit.next(50, SHARE, 45, 0, true, false)).contains(Priority.URGENT);
    }

    @Test
    void nothingWaitingMeansNothingToDispatch() {
        assertThat(CapacitySplit.next(50, SHARE, 0, 0, false, false)).isEmpty();
    }

    @Test
    void whenInitialLoadHoldsEverythingTheNextFreeSlotGoesToUrgent() {
        assertThat(CapacitySplit.next(50, SHARE, 0, 49, true, true)).contains(Priority.URGENT);
    }

    @Test
    void urgentAtItsQuotaYieldsToInitialLoadBelowItsOwn() {
        assertThat(CapacitySplit.next(50, SHARE, 40, 9, true, true)).contains(Priority.INITIAL_LOAD);
    }

    @Test
    void urgentBelowItsQuotaWinsEvenWhenInitialLoadIsAlsoBelow() {
        assertThat(CapacitySplit.next(50, SHARE, 39, 0, true, true)).contains(Priority.URGENT);
    }

    @Test
    void ifBothAreAtTheirQuotaUrgentGoesFirst() {
        assertThat(CapacitySplit.next(50, SHARE, 40, 10, true, true)).contains(Priority.URGENT);
    }

    @Test
    void zeroShareGivesUrgentNoReservedSlotWhileInitialLoadWaits() {
        assertThat(CapacitySplit.next(10, 0.0, 0, 0, true, true)).contains(Priority.INITIAL_LOAD);
        assertThat(CapacitySplit.next(50, 0.0, 0, 49, true, true)).contains(Priority.INITIAL_LOAD);
    }

    @Test
    void zeroShareStillLetsUrgentUseTheWholeLimitWhenAlone() {
        assertThat(CapacitySplit.next(10, 0.0, 9, 0, true, false)).contains(Priority.URGENT);
    }

    @Test
    void aTinyPositiveShareStillKeepsOneUrgentSlot() {
        assertThat(CapacitySplit.next(10, 0.01, 0, 0, true, true)).contains(Priority.URGENT);
        assertThat(CapacitySplit.next(10, 0.01, 1, 0, true, true)).contains(Priority.INITIAL_LOAD);
    }

    @Test
    void fullShareLeavesNoRoomForInitialLoadWhileUrgentWaits() {
        assertThat(CapacitySplit.next(10, 1.0, 9, 0, true, true)).contains(Priority.URGENT);
    }

    @Test
    void halfwayRoundsUp() {
        assertThat(CapacitySplit.next(5, 0.5, 2, 0, true, true)).contains(Priority.URGENT);
        assertThat(CapacitySplit.next(5, 0.5, 3, 0, true, true)).contains(Priority.INITIAL_LOAD);
    }
}
