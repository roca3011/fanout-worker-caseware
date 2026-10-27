package com.caseware.templateupdates.infrastructure.output;

import com.caseware.templateupdates.application.Lease;
import com.caseware.templateupdates.application.port.out.WorkQueue;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.Priority;
import com.caseware.templateupdates.domain.Reason;
import com.caseware.templateupdates.domain.WorkItem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safety: a single {@link ReentrantLock} guards all state. Methods only touch memory, so
 * the lock is never held during an external call.
 */
public final class InMemoryWorkQueue implements WorkQueue {

    private static final class Entry {
        WorkItem item;
        UUID token;
        Instant expiresAt;
        Set<Reason> photo = Set.of();

        Entry(WorkItem item) {
            this.item = item;
        }

        boolean leasedAt(Instant now) {
            return token != null && now.isBefore(expiresAt);
        }

        boolean readyAt(Instant now) {
            return !leasedAt(now) && !item.notBefore().isAfter(now);
        }
    }

    /** Entries of one priority: a FIFO per firm, served in turns (research R3). */
    private static final class Line {
        private final Map<FirmId, LinkedHashMap<EngagementId, Entry>> byFirm = new HashMap<>();
        private final List<FirmId> firms = new ArrayList<>();
        private int cursor;

        void add(Entry entry) {
            var firm = entry.item.engagement().firm();
            var queue = byFirm.get(firm);
            if (queue == null) {
                queue = new LinkedHashMap<>();
                byFirm.put(firm, queue);
                firms.add(firm);
            }
            queue.put(entry.item.engagement().id(), entry);
        }

        void remove(Entry entry) {
            var firm = entry.item.engagement().firm();
            var queue = byFirm.get(firm);
            queue.remove(entry.item.engagement().id());
            if (queue.isEmpty()) {
                byFirm.remove(firm);
                int position = firms.indexOf(firm);
                firms.remove(position);
                if (position < cursor) {
                    cursor--;
                }
            }
        }

        Optional<Entry> nextReady(Instant now) {
            int size = firms.size();
            for (int offset = 0; offset < size; offset++) {
                int position = (cursor + offset) % size;
                for (Entry entry : byFirm.get(firms.get(position)).values()) {
                    if (entry.readyAt(now)) {
                        cursor = position + 1;
                        return Optional.of(entry);
                    }
                }
            }
            return Optional.empty();
        }

        boolean anyReady(Instant now) {
            return byFirm.values().stream().flatMap(queue -> queue.values().stream()).anyMatch(e -> e.readyAt(now));
        }
    }

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<EngagementId, Entry> entries = new HashMap<>();
    private final Map<Priority, Line> lines = new EnumMap<>(Priority.class);

    public InMemoryWorkQueue(Clock clock) {
        this.clock = clock;
        for (Priority priority : Priority.values()) {
            lines.put(priority, new Line());
        }
    }

    @Override
    public void enqueue(EngagementRef engagement, Reason reason) {
        lock.lock();
        try {
            var entry = entries.get(engagement.id());
            if (entry == null) {
                entry = new Entry(WorkItem.of(engagement, reason, Instant.EPOCH));
                entries.put(engagement.id(), entry);
                lines.get(entry.item.priority()).add(entry);
            } else if (!entry.item.reasons().contains(reason)) {
                lines.get(entry.item.priority()).remove(entry);
                entry.item = entry.item.withReason(reason);
                lines.get(entry.item.priority()).add(entry);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Lease> claim(Priority priority, Instant now, Duration leaseDuration) {
        lock.lock();
        try {
            return lines.get(priority).nextReady(now).map(entry -> {
                entry.token = UUID.randomUUID();
                entry.expiresAt = now.plus(leaseDuration);
                entry.photo = entry.item.reasons();
                return new Lease(entry.item, entry.token, entry.expiresAt);
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean hasReady(Priority priority, Instant now) {
        lock.lock();
        try {
            return lines.get(priority).anyReady(now);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean ack(Lease lease) {
        lock.lock();
        try {
            var entry = validEntry(lease);
            if (entry != null) {
                var left = new HashSet<>(entry.item.reasons());
                left.removeAll(entry.photo);
                detach(entry);
                if (!left.isEmpty()) {
                    reattach(entry, new WorkItem(entry.item.engagement(), left, 0, Instant.EPOCH, null));
                }
            }
            return entry != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean retryLater(Lease lease, WorkItem updated) {
        lock.lock();
        try {
            var entry = validEntry(lease);
            if (entry != null) {
                var all = new HashSet<>(entry.item.reasons());
                all.removeAll(entry.photo);
                all.addAll(updated.reasons());
                detach(entry);
                reattach(entry, new WorkItem(updated.engagement(), all, updated.attempts(), updated.notBefore(),
                        updated.lastError()));
            }
            return entry != null;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean remove(Lease lease, Set<Reason> reasons) {
        lock.lock();
        try {
            var entry = validEntry(lease);
            if (entry != null) {
                dropReasons(entry, reasons);
            }
            return entry != null;
        } finally {
            lock.unlock();
        }
    }

    private void dropReasons(Entry entry, Set<Reason> reasons) {
        var left = new HashSet<>(entry.item.reasons());
        left.removeAll(reasons);
        var photo = new HashSet<>(entry.photo);
        photo.removeAll(reasons);
        var token = entry.token;
        var expiresAt = entry.expiresAt;
        detach(entry);
        if (!left.isEmpty()) {
            reattach(entry, new WorkItem(entry.item.engagement(), left, entry.item.attempts(),
                    entry.item.notBefore(), entry.item.lastError()));
            entry.token = token;
            entry.expiresAt = expiresAt;
            entry.photo = Set.copyOf(photo);
        }
    }

    @Override
    public long countPending(Reason job, Instant now) {
        lock.lock();
        try {
            return entries.values().stream()
                    .filter(entry -> entry.item.reasons().contains(job))
                    .filter(entry -> !(entry.leasedAt(now) && entry.photo.contains(job)))
                    .count();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long countLeased(Reason job, Instant now) {
        lock.lock();
        try {
            return entries.values().stream()
                    .filter(entry -> entry.leasedAt(now) && entry.photo.contains(job))
                    .count();
        } finally {
            lock.unlock();
        }
    }

    private Entry validEntry(Lease lease) {
        var entry = entries.get(lease.item().engagement().id());
        if (entry == null || !lease.token().equals(entry.token) || !entry.leasedAt(clock.instant())) {
            return null;
        }
        return entry;
    }

    private void detach(Entry entry) {
        lines.get(entry.item.priority()).remove(entry);
        entries.remove(entry.item.engagement().id());
    }

    private void reattach(Entry entry, WorkItem item) {
        entry.item = item;
        entry.token = null;
        entry.expiresAt = null;
        entry.photo = Set.of();
        entries.put(item.engagement().id(), entry);
        lines.get(item.priority()).add(entry);
    }
}
