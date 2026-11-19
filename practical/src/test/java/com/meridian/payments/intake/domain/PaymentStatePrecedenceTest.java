package com.meridian.payments.intake.domain;

import static com.meridian.payments.intake.domain.PaymentStatePrecedence.Decision.APPLY;
import static com.meridian.payments.intake.domain.PaymentStatePrecedence.Decision.DUPLICATE;
import static com.meridian.payments.intake.domain.PaymentStatePrecedence.Decision.SUPERSEDED;
import static com.meridian.payments.intake.domain.PaymentStatus.CAPTURED;
import static com.meridian.payments.intake.domain.PaymentStatus.FAILED;
import static com.meridian.payments.intake.domain.PaymentStatus.PENDING;
import static com.meridian.payments.intake.domain.PaymentStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.adapter.in.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.adapter.in.provider.austral.AustralAdapter;
import com.meridian.payments.intake.domain.PaymentStatePrecedence.Decision;
import com.meridian.payments.intake.domain.PaymentStatePrecedence.Snapshot;

/** FR5: a late or out-of-order notification never leaves a payment contradicting the provider's latest state. */
class PaymentStatePrecedenceTest {

    private static final Instant T0 = Instant.parse("2026-11-28T19:00:00Z");

    private static Snapshot at(PaymentStatus status, int minutes) {
        return new Snapshot(status, T0.plusSeconds(minutes * 60L), null);
    }

    private static Snapshot refund(String amount, int minutes) {
        return new Snapshot(REFUNDED, T0.plusSeconds(minutes * 60L), new Money(amount, "MXN"));
    }

    @ParameterizedTest(name = "current {0}@{1} + incoming {2}@{3} -> {4}")
    @CsvSource({
            // higher rank wins, whatever the timestamps say
            "PENDING,  0, CAPTURED, 1, APPLY",
            "CAPTURED, 1, REFUNDED, 9, APPLY",
            "PENDING,  0, FAILED,   1, APPLY",
            // late, weaker events are superseded (the INC-0412 replay risk and the PR-214 bug)
            "REFUNDED, 9, CAPTURED, 1, SUPERSEDED",
            "CAPTURED, 1, PENDING,  0, SUPERSEDED",
            "REFUNDED, 9, PENDING,  0, SUPERSEDED",
            // same fact again
            "CAPTURED, 1, CAPTURED, 1, DUPLICATE",
            "PENDING,  0, PENDING,  0, DUPLICATE",
            // anomaly: same rank, different status -> later state change wins
            "FAILED,   1, CAPTURED, 2, APPLY",
            "CAPTURED, 2, FAILED,   1, SUPERSEDED"
    })
    void decidesOnStateNotArrival(PaymentStatus current, int currentAt, PaymentStatus incoming, int incomingAt,
                                  Decision expected) {
        assertThat(PaymentStatePrecedence.decide(at(current, currentAt), at(incoming, incomingAt))).isEqualTo(expected);
    }

    @Test
    void firstEventForAPaymentIsAlwaysApplied() {
        assertThat(PaymentStatePrecedence.decide(null, at(REFUNDED, 0))).isEqualTo(APPLY);
    }

    @Test
    void successivePartialRefundsKeepTheLargestRefundedAmount() {
        Snapshot first = refund("200.00", 5);
        Snapshot second = refund("500.00", 6);

        assertThat(PaymentStatePrecedence.decide(first, second)).isEqualTo(APPLY);
        assertThat(PaymentStatePrecedence.decide(second, first)).isEqualTo(DUPLICATE);
    }

    @Test
    void finalStateIsTheSameForEveryArrivalOrder() {
        List<Snapshot> events = List.of(at(PENDING, 0), at(CAPTURED, 1), refund("1250.00", 9), at(CAPTURED, 1));

        for (List<Snapshot> order : permutations(events)) {
            assertThat(PaymentStatePrecedence.fold(order)).as("order %s", order).isEqualTo(refund("1250.00", 9));
        }
    }

    @Test
    void realProviderSamplesConvergeToRefundedInAnyOrder() {
        AndinaAdapter andina = new AndinaAdapter(TestFixtures.secrets("unused"), new ObjectMapper(), new PolicyReferenceNormalizer());
        AustralAdapter austral = new AustralAdapter(TestFixtures.secrets("unused"), new ObjectMapper(), new PolicyReferenceNormalizer());

        List<Snapshot> colombia = TestFixtures.ALL_ANDINA.stream()
                .map(v -> Snapshot.of(andina.toCanonical(v.body()))).toList();
        List<Snapshot> chile = TestFixtures.ALL_AUSTRAL.stream()
                .map(v -> Snapshot.of(austral.toCanonical(v.body()))).toList();

        permutations(colombia).forEach(order -> assertThat(PaymentStatePrecedence.fold(order).status()).isEqualTo(REFUNDED));
        permutations(chile).forEach(order -> assertThat(PaymentStatePrecedence.fold(order).status()).isEqualTo(REFUNDED));
    }

    @Test
    void decisionsAreConsistentInBothDirections() {
        // For two different facts, exactly one of them can win: never both APPLY.
        for (PaymentStatus a : PaymentStatus.values()) {
            for (PaymentStatus b : PaymentStatus.values()) {
                if (a == b) {
                    continue;
                }
                Decision ab = PaymentStatePrecedence.decide(at(a, 1), at(b, 2));
                Decision ba = PaymentStatePrecedence.decide(at(b, 2), at(a, 1));
                assertThat(ab == APPLY && ba == APPLY).as("%s vs %s", a, b).isFalse();
                assertThat(ab == SUPERSEDED && ba == SUPERSEDED).as("%s vs %s", a, b).isFalse();
            }
        }
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> permutation = new ArrayList<>();
                permutation.add(head);
                permutation.addAll(tail);
                result.add(permutation);
            }
        }
        return result;
    }
}
