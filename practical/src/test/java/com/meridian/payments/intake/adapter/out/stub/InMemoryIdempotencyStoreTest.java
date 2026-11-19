package com.meridian.payments.intake.adapter.out.stub;

import static com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult.ALREADY_PUBLISHED;
import static com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult.CLAIMED_NOT_PUBLISHED;
import static com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult.IN_FLIGHT;
import static com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult.NEW;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.adapter.in.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.application.port.out.IdempotencyStore.ClaimResult;
import com.meridian.payments.intake.domain.PolicyReferenceNormalizer;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/** The lease semantics the Azure SQL implementation must also honour. */
class InMemoryIdempotencyStoreTest {

    private static final Instant NOW = Instant.parse("2026-11-28T18:00:04Z");
    private static final PremiumPaymentEvent EVENT = PremiumPaymentEvent.wrap(
            new QuetzalAdapter(TestFixtures.secrets("unused"), new ObjectMapper(), new PolicyReferenceNormalizer())
                    .toCanonical(TestFixtures.QZ_SUCCEEDED.body()),
            "quetzal", NOW, "corr");
    private static final String KEY = EVENT.data().idempotencyKey();

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(Duration.ofSeconds(10));

    @Test
    void aLiveLeaseBlocksAConcurrentDeliveryUntilItExpires() {
        assertThat(store.claim(EVENT, NOW)).isEqualTo(NEW);
        assertThat(store.claim(EVENT, NOW.plusSeconds(9))).isEqualTo(IN_FLIGHT);
        // The first delivery crashed without publishing: after the lease, a retry takes over.
        assertThat(store.claim(EVENT, NOW.plusSeconds(11))).isEqualTo(CLAIMED_NOT_PUBLISHED);
    }

    @Test
    void releasingAfterAFailedPublishLetsTheRetryInImmediately() {
        store.claim(EVENT, NOW);
        store.release(KEY);

        assertThat(store.claim(EVENT, NOW.plusSeconds(1))).isEqualTo(CLAIMED_NOT_PUBLISHED);
    }

    @Test
    void oncePublishedEveryDeliveryIsADuplicate() {
        store.claim(EVENT, NOW);
        store.markPublished(KEY);
        store.release(KEY); // a late release must not reopen a published event

        assertThat(store.claim(EVENT, NOW.plusSeconds(1))).isEqualTo(ALREADY_PUBLISHED);
        assertThat(store.claim(EVENT, NOW.plusSeconds(3600))).isEqualTo(ALREADY_PUBLISHED);
    }

    @Test
    void exactlyOneOfManyConcurrentFirstDeliveriesGetsTheLease() throws Exception {
        List<Callable<ClaimResult>> deliveries = IntStream.range(0, 32)
                .<Callable<ClaimResult>>mapToObj(i -> () -> store.claim(EVENT, NOW))
                .toList();

        List<ClaimResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            results = executor.invokeAll(deliveries).stream().map(InMemoryIdempotencyStoreTest::get).toList();
        }

        assertThat(results).filteredOn(r -> r == NEW).hasSize(1);
        assertThat(results).filteredOn(r -> r == IN_FLIGHT).hasSize(31);
    }

    private static ClaimResult get(Future<ClaimResult> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
