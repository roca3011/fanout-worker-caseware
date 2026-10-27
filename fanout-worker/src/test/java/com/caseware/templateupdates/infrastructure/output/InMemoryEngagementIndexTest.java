package com.caseware.templateupdates.infrastructure.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.caseware.templateupdates.domain.EngagementId;
import com.caseware.templateupdates.domain.EngagementRef;
import com.caseware.templateupdates.domain.FirmId;
import com.caseware.templateupdates.domain.TemplateId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InMemoryEngagementIndexTest {

    private static final TemplateId T1 = new TemplateId("T1");
    private static final TemplateId T2 = new TemplateId("T2");
    private static final EngagementRef E1 = ref("E1", T1);
    private static final EngagementRef E2 = ref("E2", T1);
    private static final EngagementRef E3 = ref("E3", T2);

    private static EngagementRef ref(String id, TemplateId template) {
        return new EngagementRef(new EngagementId(id), new FirmId("F1"), template);
    }

    private final InMemoryEngagementIndex index = new InMemoryEngagementIndex(List.of(E1, E2, E3));

    @Test
    void unannotatedListsOnlyEngagementsOfTheTemplateWithoutAnnotation() {
        assertThat(index.unannotated(T1)).containsExactlyInAnyOrder(E1, E2);
        index.annotate(E1.id(), "v2", 1);
        assertThat(index.unannotated(T1)).containsExactly(E2);
        assertThat(index.unannotated(T2)).containsExactly(E3);
    }

    @Test
    void unannotatedOfUnknownTemplateIsEmpty() {
        assertThat(index.unannotated(new TemplateId("none"))).isEmpty();
    }

    @Test
    void annotationKeepsVersionAndSeq() {
        index.annotate(E1.id(), "v2", 12);
        var annotation = index.annotation(E1.id()).orElseThrow();
        assertThat(annotation.version()).isEqualTo("v2");
        assertThat(annotation.seq()).isEqualTo(12);
    }

    @Test
    void olderSeqDoesNotOverwriteNewer() {
        index.annotate(E1.id(), "v12", 12);
        index.annotate(E1.id(), "v11", 11);
        assertThat(index.annotation(E1.id()).orElseThrow().version()).isEqualTo("v12");
    }

    @Test
    void sameSeqDoesNotOverwrite() {
        index.annotate(E1.id(), "first", 5);
        index.annotate(E1.id(), "second", 5);
        assertThat(index.annotation(E1.id()).orElseThrow().version()).isEqualTo("first");
    }

    @Test
    void newerSeqOverwrites() {
        index.annotate(E1.id(), "v5", 5);
        index.annotate(E1.id(), "v7", 7);
        assertThat(index.annotation(E1.id()).orElseThrow().version()).isEqualTo("v7");
    }

    @Test
    void noAnnotationBeforeAnnotate() {
        assertThat(index.annotation(E1.id())).isEmpty();
    }

    @Test
    void concurrentUnorderedAnnotationsKeepTheGreatestSeq() throws Exception {
        List<Long> seqs = new ArrayList<>();
        for (long s = 1; s <= 400; s++) {
            seqs.add(s);
        }
        Collections.shuffle(seqs, new java.util.Random(7));
        var start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (long s : seqs) {
                futures.add(pool.submit(() -> {
                    start.await();
                    index.annotate(E1.id(), "v" + s, s);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        var annotation = index.annotation(E1.id()).orElseThrow();
        assertThat(annotation.seq()).isEqualTo(400);
        assertThat(annotation.version()).isEqualTo("v400");
    }
}
