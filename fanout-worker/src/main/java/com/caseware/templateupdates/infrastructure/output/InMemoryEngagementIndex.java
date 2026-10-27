package com.caseware.templateupdates.infrastructure.output;

import com.caseware.templateupdates.application.port.out.EngagementIndex;
import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.TemplateId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safety: annotations live in a {@link ConcurrentHashMap}; {@code merge} is atomic per
 * key, so the highest {@code seq} always wins regardless of arrival order.
 */
public final class InMemoryEngagementIndex implements EngagementIndex {

    public record Annotation(String version, long seq) {}

    private final List<EngagementRef> engagements;
    private final Map<EngagementId, Annotation> annotations = new ConcurrentHashMap<>();

    public InMemoryEngagementIndex(Collection<EngagementRef> engagements) {
        this.engagements = List.copyOf(engagements);
    }

    @Override
    public List<EngagementRef> unannotated(TemplateId template) {
        return engagements.stream()
                .filter(ref -> ref.template().equals(template))
                .filter(ref -> !annotations.containsKey(ref.id()))
                .toList();
    }

    @Override
    public void annotate(EngagementId id, String version, long seq) {
        annotations.merge(id, new Annotation(version, seq),
                (current, incoming) -> incoming.seq() > current.seq() ? incoming : current);
    }

    public Optional<Annotation> annotation(EngagementId id) {
        return Optional.ofNullable(annotations.get(id));
    }
}
