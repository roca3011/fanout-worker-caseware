package com.caseware.templateupdates.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.InitialLoad;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.RetryBudget;
import com.caseware.templateupdates.domain.RetryPolicy;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.domain.TemplatePublished;
import com.caseware.templateupdates.fixtures.DirectExecutor;
import com.caseware.templateupdates.fixtures.MutableClock;
import com.caseware.templateupdates.fixtures.RecordingAlertSink;
import com.caseware.templateupdates.fixtures.ScriptedEngagementSystem;
import com.caseware.templateupdates.fixtures.TestSettings;
import com.caseware.templateupdates.infrastructure.output.InMemoryEngagementIndex;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkResults;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class FanOutWorkerTest {

    private static final Reason INITIAL = new InitialLoad();
    private static final TemplateId TEMPLATE = new TemplateId("T1");
    private static final Reason PUBLISHED = new TemplatePublished(TEMPLATE, "v2");

    private final MutableClock clock = new MutableClock();
    private final SettingsHolder settings = new SettingsHolder(TestSettings.withConcurrency(2));
    private final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    private final InMemoryWorkResults results = new InMemoryWorkResults();
    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of());
    private final ScriptedEngagementSystem system = new ScriptedEngagementSystem();
    private final RecordingAlertSink alerts = new RecordingAlertSink();
    private final ConcurrencyLimiter limiter = new ConcurrencyLimiter(settings);
    private final ItemProcessor processor =
            new ItemProcessor(queue, results, index, system, alerts, settings, limiter, clock,
                    new RetryPolicy(new Random(1)), new RetryBudget(clock));

    private static EngagementRef ref(String id) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), TEMPLATE);
    }

    private FanOutWorker worker(Executor executor) {
        return new FanOutWorker(queue, processor, limiter, settings, clock, executor, null, Duration.ofMillis(10),
                Duration.ofSeconds(2));
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void dispatchOnceWithNothingToDoReturnsFalse() {
        assertThat(worker(new DirectExecutor()).dispatchOnce()).isFalse();
        assertThat(system.totalCalls()).isZero();
    }

    @Test
    void dispatchOnceProcessesOneReadyEntry() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E2"), INITIAL);

        assertThat(worker(new DirectExecutor()).dispatchOnce()).isTrue();

        assertThat(results.countCompleted(INITIAL)).isEqualTo(1);
        assertThat(system.callsTo(new EngagementId("E1"))).isEqualTo(1);
    }

    @Test
    void urgentEntriesAreTakenBeforeInitialLoad() {
        queue.enqueue(ref("E1"), INITIAL);
        queue.enqueue(ref("E2"), PUBLISHED);

        worker(new DirectExecutor()).dispatchOnce();

        assertThat(results.countCompleted(PUBLISHED)).isEqualTo(1);
        assertThat(results.countCompleted(INITIAL)).isZero();
    }

    @Test
    void whenThereAreNoUrgentEntriesItTakesInitialLoad() {
        queue.enqueue(ref("E1"), INITIAL);
        worker(new DirectExecutor()).dispatchOnce();
        assertThat(results.countCompleted(INITIAL)).isEqualTo(1);
    }

    @Test
    void handsTheCallToTheExecutorAndHoldsTheSlotUntilItFinishes() {
        queue.enqueue(ref("E1"), INITIAL);
        List<Runnable> submitted = new ArrayList<>();

        assertThat(worker(submitted::add).dispatchOnce()).isTrue();

        assertThat(submitted).hasSize(1);
        assertThat(system.totalCalls()).isZero();
        assertThat(limiter.inFlight()).isEqualTo(1);
        assertThat(queue.countLeased(INITIAL, clock.instant())).isEqualTo(1);

        submitted.get(0).run();

        assertThat(limiter.inFlight()).isZero();
        assertThat(results.countCompleted(INITIAL)).isEqualTo(1);
    }

    @Test
    void doesNotClaimWhenThereIsNoCapacity() {
        queue.enqueue(ref("E1"), INITIAL);
        limiter.tryAcquire(Priority.URGENT);
        limiter.tryAcquire(Priority.URGENT);

        assertThat(worker(new DirectExecutor()).dispatchOnce()).isFalse();

        assertThat(system.totalCalls()).isZero();
        assertThat(queue.countLeased(INITIAL, clock.instant())).isZero();
        assertThat(queue.hasReady(Priority.INITIAL_LOAD, clock.instant())).isTrue();
    }

    @Test
    void anUnexpectedFailureInATaskDoesNotPropagateToTheDispatcher() {
        queue.enqueue(ref("E1"), INITIAL);
        system.onCall(engagement -> {
            throw new IllegalStateException("boom");
        });

        assertThat(worker(new DirectExecutor()).dispatchOnce()).isTrue();
    }

    @Test
    void defaultExecutorRunsCallsOnVirtualThreads() throws Exception {
        var virtual = new AtomicBoolean();
        system.onCall(engagement -> virtual.set(Thread.currentThread().isVirtual()));
        var worker = FanOutWorker.create(queue, results, index, system, alerts, settings, clock, new Random(1));
        queue.enqueue(ref("E1"), INITIAL);

        worker.start();
        awaitTrue(() -> results.countCompleted(INITIAL) == 1);
        worker.close();

        assertThat(virtual).isTrue();
    }

    @Test
    void runLoopProcessesEverythingAndStopsOnClose() throws Exception {
        for (int i = 0; i < 20; i++) {
            queue.enqueue(ref("E" + i), INITIAL);
        }
        var worker = worker(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());

        worker.start();
        awaitTrue(() -> results.countCompleted(INITIAL) == 20);
        worker.close();

        queue.enqueue(ref("late"), INITIAL);
        assertThat(worker.dispatchOnce()).isFalse();
        assertThat(system.callsTo(new EngagementId("late"))).isZero();
    }

    @Test
    void closeWaitsForCallsInFlightButNotForever() throws Exception {
        system.holdCalls();
        queue.enqueue(ref("E1"), INITIAL);
        var worker = new FanOutWorker(queue, processor, limiter, settings, clock,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), null, Duration.ofMillis(10),
                Duration.ofMillis(150));
        worker.start();
        assertThat(system.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();

        long started = System.nanoTime();
        worker.close();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(140).isLessThan(3_000);
        system.releaseAll();
        awaitTrue(() -> results.countCompleted(INITIAL) == 1);
    }

    @Test
    void closeReturnsRightAfterTheCallsFinish() throws Exception {
        queue.enqueue(ref("E1"), INITIAL);
        var worker = worker(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        worker.start();
        awaitTrue(() -> results.countCompleted(INITIAL) == 1);

        long started = System.nanoTime();
        worker.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(1_000);
    }

    @Test
    void theCreateVariantWithAnExplicitExecutorUsesIt() {
        queue.enqueue(ref("E1"), INITIAL);
        var worker = FanOutWorker.create(queue, results, index, system, alerts, settings, clock, new Random(1),
                new DirectExecutor());

        assertThat(worker.dispatchOnce()).isTrue();

        assertThat(results.countCompleted(INITIAL)).isEqualTo(1);
    }

    @Test
    void ifTheQueueHasNothingToClaimTheSlotIsGivenBack() {
        var emptyHanded = new com.caseware.templateupdates.application.port.out.WorkQueue() {
            @Override
            public void enqueue(EngagementRef engagement, Reason reason) {
                queue.enqueue(engagement, reason);
            }

            @Override
            public java.util.Optional<Lease> claim(Priority priority, java.time.Instant now, Duration leaseDuration) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean hasReady(Priority priority, java.time.Instant now) {
                return true;
            }

            @Override
            public boolean ack(Lease lease) {
                return queue.ack(lease);
            }

            @Override
            public boolean retryLater(Lease lease, com.caseware.templateupdates.domain.WorkItem updated) {
                return queue.retryLater(lease, updated);
            }

            @Override
            public boolean remove(Lease lease, java.util.Set<Reason> reasons) {
                return queue.remove(lease, reasons);
            }

            @Override
            public long countPending(Reason job, java.time.Instant now) {
                return queue.countPending(job, now);
            }

            @Override
            public long countLeased(Reason job, java.time.Instant now) {
                return queue.countLeased(job, now);
            }
        };
        var worker = new FanOutWorker(emptyHanded, processor, limiter, settings, clock, new DirectExecutor(), null,
                Duration.ofMillis(10), Duration.ofSeconds(1));

        assertThat(worker.dispatchOnce()).isFalse();

        assertThat(limiter.inFlight()).isZero();
    }

    @Test
    void interruptingTheLoopStopsItAndKeepsTheInterruptFlag() throws Exception {
        var worker = worker(new DirectExecutor());
        var flag = new AtomicBoolean();
        var thread = new Thread(() -> {
            worker.run();
            flag.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        Thread.sleep(50);

        thread.interrupt();
        thread.join(5_000);

        assertThat(thread.isAlive()).isFalse();
        assertThat(flag).isTrue();
    }

    @Test
    void closeReturnsAsSoonAsTheLastCallFinishesNotAtTheTimeout() throws Exception {
        system.holdCalls();
        queue.enqueue(ref("E1"), INITIAL);
        var worker = new FanOutWorker(queue, processor, limiter, settings, clock,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), null, Duration.ofMillis(10),
                Duration.ofSeconds(20));
        worker.start();
        assertThat(system.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();
        var closer = Thread.ofVirtual().start(worker::close);
        Thread.sleep(100);

        system.releaseAll();
        closer.join(3_000);

        assertThat(closer.isAlive()).isFalse();
    }

    @Test
    void closeKeepsTheInterruptFlagOfTheCallingThread() throws Exception {
        system.holdCalls();
        queue.enqueue(ref("E1"), INITIAL);
        var worker = new FanOutWorker(queue, processor, limiter, settings, clock,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), null, Duration.ofMillis(10),
                Duration.ofMillis(300));
        worker.start();
        assertThat(system.awaitInFlight(1, Duration.ofSeconds(5))).isTrue();
        var flag = new AtomicBoolean();
        var thread = new Thread(() -> {
            Thread.currentThread().interrupt();
            worker.close();
            flag.set(Thread.currentThread().isInterrupted());
        });

        thread.start();
        thread.join(5_000);
        system.releaseAll();

        assertThat(flag).isTrue();
    }
}
