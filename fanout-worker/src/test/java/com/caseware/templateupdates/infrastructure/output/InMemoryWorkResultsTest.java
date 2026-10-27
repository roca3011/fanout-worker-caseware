package com.caseware.templateupdates.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.Result;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryWorkResultsTest {

    private static final EngagementId E1 = new EngagementId("E1");
    private static final EngagementId E2 = new EngagementId("E2");
    private static final Reason PUBLISHED = new TemplatePublished(new TemplateId("T1"), "v2");
    private static final Reason INITIAL = new InitialLoad();

    private final InMemoryWorkResults results = new InMemoryWorkResults();

    @Test
    void completedReasonsReturnsOnlyCandidatesAlreadyCompleted() {
        results.recordCompleted(E1, Set.of(PUBLISHED), "v2", 1);
        assertThat(results.completedReasons(E1, Set.of(PUBLISHED, INITIAL))).containsExactly(PUBLISHED);
        assertThat(results.completedReasons(E2, Set.of(PUBLISHED))).isEmpty();
    }

    @Test
    void failedReasonsAreNotReportedAsCompleted() {
        results.recordFailed(E1, Set.of(PUBLISHED), "boom", 3);
        assertThat(results.completedReasons(E1, Set.of(PUBLISHED))).isEmpty();
    }

    @Test
    void recordingCompletedTwiceChangesNothing() {
        results.recordCompleted(E1, Set.of(PUBLISHED), "v2", 1);
        results.recordCompleted(E1, Set.of(PUBLISHED), "v2", 1);
        assertThat(results.countCompleted(PUBLISHED)).isEqualTo(1);
    }

    @Test
    void completedIsRecordedPerReason() {
        results.recordCompleted(E1, Set.of(PUBLISHED, INITIAL), "v2", 1);
        assertThat(results.countCompleted(PUBLISHED)).isEqualTo(1);
        assertThat(results.countCompleted(INITIAL)).isEqualTo(1);
    }

    @Test
    void failedDoesNotOverwriteCompleted() {
        results.recordCompleted(E1, Set.of(PUBLISHED), "v2", 1);
        results.recordFailed(E1, Set.of(PUBLISHED), "late", 3);
        assertThat(results.countFailed(PUBLISHED)).isZero();
        assertThat(results.countCompleted(PUBLISHED)).isEqualTo(1);
    }

    @Test
    void completedOverwritesAPreviousFailure() {
        results.recordFailed(E1, Set.of(PUBLISHED), "boom", 3);
        results.recordCompleted(E1, Set.of(PUBLISHED), "v2", 1);
        assertThat(results.countFailed(PUBLISHED)).isZero();
        assertThat(results.countCompleted(PUBLISHED)).isEqualTo(1);
    }

    @Test
    void failedListsErrorAndAttemptsOfTheJobOnly() {
        results.recordFailed(E1, Set.of(PUBLISHED), "boom", 3);
        results.recordFailed(E2, Set.of(INITIAL), "other", 1);
        assertThat(results.failed(PUBLISHED))
                .containsExactly(new Result.Failed(E1, PUBLISHED, "boom", 3));
        assertThat(results.countFailed(PUBLISHED)).isEqualTo(1);
        assertThat(results.countFailed(INITIAL)).isEqualTo(1);
    }

    @Test
    void completedKeepsVersionAndAttempts() {
        results.recordCompleted(E1, Set.of(PUBLISHED), "v9", 2);
        assertThat(results.completed(PUBLISHED))
                .containsExactly(new Result.Completed(E1, PUBLISHED, "v9", 2));
    }

    @Test
    void concurrentCompletedAndFailedLeaveASingleCompleted() throws Exception {
        int threads = 16;
        var start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final boolean completes = i % 2 == 0;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < 200; n++) {
                        if (completes) {
                            results.recordCompleted(E1, Set.of(PUBLISHED), "v2", 1);
                        } else {
                            results.recordFailed(E1, Set.of(PUBLISHED), "boom", 3);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(results.countCompleted(PUBLISHED)).isEqualTo(1);
        assertThat(results.countFailed(PUBLISHED)).isZero();
        assertThat(results.completedReasons(E1, Set.of(PUBLISHED))).containsExactly(PUBLISHED);
    }
}
