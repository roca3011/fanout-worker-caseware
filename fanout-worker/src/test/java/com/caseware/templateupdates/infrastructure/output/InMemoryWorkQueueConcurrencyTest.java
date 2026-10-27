package com.caseware.templateupdates.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.application.Lease;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.fixtures.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryWorkQueueConcurrencyTest {

    @Test
    void concurrentClaimsNeverLeaseTheSameEntryTwiceNorLoseOne() throws Exception {
        int entries = 2_000;
        int threads = 16;
        var clock = new MutableClock();
        var queue = new InMemoryWorkQueue(clock);
        for (int i = 0; i < entries; i++) {
            queue.enqueue(new EngagementRef(new EngagementId("E" + i), new FirmId("F" + (i % 7)),
                    new TemplateId("T1")), new InitialLoad());
        }
        Set<EngagementId> claimed = ConcurrentHashMap.newKeySet();
        var duplicates = new java.util.concurrent.atomic.AtomicInteger();
        var start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    while (true) {
                        var lease = queue.claim(Priority.INITIAL_LOAD, clock.instant(), Duration.ofMinutes(6));
                        if (lease.isEmpty()) {
                            return null;
                        }
                        Lease l = lease.get();
                        if (!claimed.add(l.item().engagement().id())) {
                            duplicates.incrementAndGet();
                        }
                        queue.ack(l);
                    }
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(duplicates).hasValue(0);
        assertThat(claimed).hasSize(entries);
        assertThat(queue.countPending(new InitialLoad(), clock.instant())).isZero();
    }
}
