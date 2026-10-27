package com.caseware.templateupdates.application;

import com.caseware.templateupdates.application.ConcurrencyLimiter.Permit;
import com.caseware.templateupdates.application.ConcurrencyLimiter.Response;
import com.caseware.templateupdates.application.port.out.Alert;
import com.caseware.templateupdates.application.port.out.AlertSink;
import com.caseware.templateupdates.application.port.out.AlertType;
import com.caseware.templateupdates.application.port.out.EngagementIndex;
import com.caseware.templateupdates.application.port.out.EngagementSystem;
import com.caseware.templateupdates.application.port.out.WorkQueue;
import com.caseware.templateupdates.application.port.out.WorkResults;
import com.caseware.templateupdates.domain.LoadOutcome;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.RetryBudget;
import com.caseware.templateupdates.domain.RetryDecision;
import com.caseware.templateupdates.domain.RetryPolicy;
import com.caseware.templateupdates.domain.WorkItem;
import java.time.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Processes one leased engagement end to end, without owning any thread (research R12). */
public final class ItemProcessor {

    private static final System.Logger LOG = System.getLogger(ItemProcessor.class.getName());

    private final WorkQueue queue;
    private final WorkResults results;
    private final EngagementIndex index;
    private final EngagementSystem system;
    private final AlertSink alerts;
    private final SettingsHolder settings;
    private final ConcurrencyLimiter limiter;
    private final Clock clock;
    private final RetryPolicy retryPolicy;
    private final RetryBudget retryBudget;

    public ItemProcessor(
            WorkQueue queue,
            WorkResults results,
            EngagementIndex index,
            EngagementSystem system,
            AlertSink alerts,
            SettingsHolder settings,
            ConcurrencyLimiter limiter,
            Clock clock,
            RetryPolicy retryPolicy,
            RetryBudget retryBudget) {
        this.queue = queue;
        this.results = results;
        this.index = index;
        this.system = system;
        this.alerts = alerts;
        this.settings = settings;
        this.limiter = limiter;
        this.clock = clock;
        this.retryPolicy = retryPolicy;
        this.retryBudget = retryBudget;
    }

    /** Never throws: an unexpected failure is logged and the lease is left to expire (research R10). */
    public void process(Lease lease, Permit permit) {
        var held = new HeldPermit(permit);
        try {
            run(lease, held);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "processing failed engagement=" + lease.item().engagement().id().value(), e);
        } finally {
            held.releaseIfHeld();
        }
    }

    private void run(Lease lease, HeldPermit held) {
        var item = withoutCompleted(lease);
        if (item.isEmpty()) {
            return;
        }
        var outcome = load(item.get(), held);
        apply(lease, item.get(), outcome);
    }

    /** CA3: reasons already completed are dropped from the queue and never called again. */
    private Optional<WorkItem> withoutCompleted(Lease lease) {
        var item = lease.item();
        Set<Reason> done = results.completedReasons(item.engagement().id(), item.reasons());
        if (done.isEmpty()) {
            return Optional.of(item);
        }
        if (!queue.remove(lease, done)) {
            logLostLease(lease, "remove");
            return Optional.empty();
        }
        var left = new HashSet<>(item.reasons());
        left.removeAll(done);
        return left.isEmpty() ? Optional.empty() : Optional.of(item.withoutReasons(done));
    }

    private LoadOutcome load(WorkItem item, HeldPermit held) {
        var current = settings.current();
        LoadOutcome outcome;
        try {
            outcome = system.load(item.engagement(), current.responseTimeout());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR,
                    "unexpected failure from engagement system engagement=" + item.engagement().id().value(), e);
            outcome = new LoadOutcome.Transient("unexpected: " + e.getClass().getSimpleName());
        }
        retryBudget.recordCall(current.retryBudgetWindow());
        if (held.release(responseOf(outcome))) {
            alerts.raise(new Alert(AlertType.OVERLOADED, "concurrency limit lowered to " + limiter.currentLimit()));
        }
        return outcome;
    }

    private static Response responseOf(LoadOutcome outcome) {
        return switch (outcome) {
            case LoadOutcome.Loaded loaded -> Response.NORMAL;
            case LoadOutcome.Permanent failure -> Response.NORMAL;
            case LoadOutcome.Overloaded overloaded -> Response.OVERLOADED;
            case LoadOutcome.Transient failure -> Response.NEUTRAL;
        };
    }

    private void apply(Lease lease, WorkItem item, LoadOutcome outcome) {
        var now = clock.instant();
        switch (decide(item, outcome)) {
            case RetryDecision.Complete done -> complete(lease, item, done);
            case RetryDecision.RetryAfter retry -> retryLater(lease, item.nextAttempt(now.plus(retry.delay()),
                    ((LoadOutcome.Transient) outcome).reason()));
            case RetryDecision.Requeue requeue -> retryLater(lease, item);
            case RetryDecision.Defer defer -> retryLater(lease, item.deferredUntil(now.plus(defer.delay())));
            case RetryDecision.Fail failure -> fail(lease, item, failure.reason());
        }
    }

    private RetryDecision decide(WorkItem item, LoadOutcome outcome) {
        return switch (outcome) {
            case LoadOutcome.Loaded loaded -> new RetryDecision.Complete(loaded.version(), loaded.seq());
            case LoadOutcome.Permanent failure -> new RetryDecision.Fail(failure.reason());
            case LoadOutcome.Overloaded overloaded -> withBudget(new RetryDecision.Requeue());
            case LoadOutcome.Transient failure -> decideTransient(item, failure);
        };
    }

    private RetryDecision decideTransient(WorkItem item, LoadOutcome.Transient failure) {
        var current = settings.current();
        return retryPolicy
                .nextDelay(item.attempts() + 1, current.maxAttempts(), current.retryDelays())
                .<RetryDecision>map(delay -> withBudget(new RetryDecision.RetryAfter(delay)))
                .orElseGet(() -> new RetryDecision.Fail(failure.reason()));
    }

    /** CA11: keeps the planned retry only while the budget allows it, else sets the entry aside. */
    private RetryDecision withBudget(RetryDecision planned) {
        var current = settings.current();
        var verdict = retryBudget.requestRetry(
                current.retryBudgetWindow(), current.retryBudgetRatio(), current.retryBudgetMinRetries());
        if (verdict == RetryBudget.Verdict.ALLOWED) {
            return planned;
        }
        if (verdict == RetryBudget.Verdict.EXHAUSTED_FIRST) {
            alerts.raise(new Alert(AlertType.RETRY_BUDGET_EXHAUSTED,
                    "retry budget exhausted; deferring for " + current.retryBudgetWindow()));
        }
        return new RetryDecision.Defer(current.retryBudgetWindow());
    }

    private void complete(Lease lease, WorkItem item, RetryDecision.Complete done) {
        var id = item.engagement().id();
        index.annotate(id, done.version(), done.seq());
        results.recordCompleted(id, item.reasons(), done.version(), item.attempts() + 1);
        confirm(queue.ack(lease), lease, "ack");
        LOG.log(System.Logger.Level.DEBUG, "completed engagement={0} version={1}", id.value(), done.version());
    }

    private void fail(Lease lease, WorkItem item, String error) {
        var id = item.engagement().id();
        results.recordFailed(id, item.reasons(), error, item.attempts() + 1);
        confirm(queue.ack(lease), lease, "ack");
        LOG.log(System.Logger.Level.WARNING, "failed engagement={0} error={1}", id.value(), error);
    }

    private void retryLater(Lease lease, WorkItem updated) {
        confirm(queue.retryLater(lease, updated), lease, "retryLater");
    }

    private void confirm(boolean accepted, Lease lease, String operation) {
        if (!accepted) {
            logLostLease(lease, operation);
        }
    }

    private static void logLostLease(Lease lease, String operation) {
        LOG.log(System.Logger.Level.WARNING, "lease lost on {0} engagement={1}", operation,
                lease.item().engagement().id().value());
    }

    /** The slot is released exactly once, as soon as the call returns or the processing ends. */
    private final class HeldPermit {
        private final Permit permit;
        private boolean released;

        HeldPermit(Permit permit) {
            this.permit = permit;
        }

        boolean release(Response response) {
            released = true;
            return limiter.release(permit, response);
        }

        void releaseIfHeld() {
            if (!released) {
                release(Response.NEUTRAL);
            }
        }
    }
}
