package com.caseware.templateupdates.fixtures;

import com.caseware.templateupdates.application.ConcurrencyLimiter;
import com.caseware.templateupdates.application.ConcurrencyLimiter.Permit;
import com.caseware.templateupdates.application.FanOutSettings;
import com.caseware.templateupdates.application.ItemProcessor;
import com.caseware.templateupdates.application.Lease;
import com.caseware.templateupdates.application.SettingsHolder;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.RetryBudget;
import com.caseware.templateupdates.domain.RetryPolicy;
import com.caseware.templateupdates.domain.TemplateId;
import com.caseware.templateupdates.infrastructure.output.InMemoryEngagementIndex;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkQueue;
import com.caseware.templateupdates.infrastructure.output.InMemoryWorkResults;
import java.util.List;
import java.util.Random;

/** Wires an {@link ItemProcessor} over in-memory adapters, with a seeded random and a mutable clock. */
public final class ProcessorHarness {

    public record Work(Lease lease, Permit permit) {}

    public final MutableClock clock = new MutableClock();
    public final SettingsHolder settings;
    public final InMemoryWorkQueue queue = new InMemoryWorkQueue(clock);
    public final InMemoryWorkResults results = new InMemoryWorkResults();
    public final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of());
    public final ScriptedEngagementSystem system = new ScriptedEngagementSystem();
    public final RecordingAlertSink alerts = new RecordingAlertSink();
    public final ConcurrencyLimiter limiter;
    public final ItemProcessor processor;

    public ProcessorHarness(FanOutSettings initial) {
        this.settings = new SettingsHolder(initial);
        this.limiter = new ConcurrencyLimiter(settings);
        this.processor = new ItemProcessor(queue, results, index, system, alerts, settings, limiter, clock,
                new RetryPolicy(new Random(1)), new RetryBudget(clock));
    }

    public static EngagementRef ref(String id) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), new TemplateId("T1"));
    }

    public Work take(Priority priority) {
        var permit = limiter.tryAcquire(priority).orElseThrow();
        var lease = queue.claim(priority, clock.instant(), settings.current().leaseDuration()).orElseThrow();
        return new Work(lease, permit);
    }

    public Work enqueueAndTake(String id, Reason reason) {
        queue.enqueue(ref(id), reason);
        return take(reason.priority());
    }

    public void process(Work work) {
        processor.process(work.lease(), work.permit());
    }
}
