package com.meridian.payments.intake.adapter.in.web;

import static com.meridian.payments.intake.TestFixtures.ANDINA_TEST_SECRET;
import static com.meridian.payments.intake.TestFixtures.AN_APPROVED;
import static com.meridian.payments.intake.TestFixtures.AN_APPROVED_RETRY;
import static com.meridian.payments.intake.TestFixtures.AN_PENDING;
import static com.meridian.payments.intake.TestFixtures.AN_REVERSED;
import static com.meridian.payments.intake.TestFixtures.QUETZAL_TEST_SECRET;
import static com.meridian.payments.intake.TestFixtures.QZ_REFUNDED;
import static com.meridian.payments.intake.TestFixtures.QZ_SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.TestFixtures.MutableClock;
import com.meridian.payments.intake.TestFixtures.Vector;
import com.meridian.payments.intake.domain.PaymentStatus;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;
import com.meridian.payments.intake.application.port.out.IdempotencyStore.AttemptOutcome;
import com.meridian.payments.intake.application.port.out.PublishException;
import com.meridian.payments.intake.application.port.out.StoreUnavailableException;
import com.meridian.payments.intake.adapter.out.stub.InMemoryEventPublisher;
import com.meridian.payments.intake.adapter.out.stub.InMemoryIdempotencyStore;

/**
 * End-to-end through HTTP with the stubs: the behaviours that matter for this integration
 * (authenticity, no loss, exactly-once publication, no personal data in logs).
 */
@SpringBootTest(properties = {
        "intake.secrets.quetzal-webhook=" + QUETZAL_TEST_SECRET,
        "intake.secrets.andina-webhook=" + ANDINA_TEST_SECRET})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class WebhookIntakeTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.EPOCH);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MutableClock clock;
    @MockitoSpyBean
    private InMemoryEventPublisher publisher;
    @MockitoSpyBean
    private InMemoryIdempotencyStore store;

    @BeforeEach
    void reset() {
        publisher.clear();
        store.clear();
    }

    private ResultActions send(String provider, Vector vector) throws Exception {
        clock.set(vector.signedAt().plusSeconds(1));
        return send(provider, vector.body(), vector.headers());
    }

    private ResultActions send(String provider, byte[] body, HttpHeaders headers) throws Exception {
        return mvc.perform(post("/webhooks/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .header("X-Correlation-Id", "test-corr-1")
                .content(body));
    }

    @Test
    void validNotificationIsPublishedOnceAndAcknowledgedWith200() throws Exception {
        send("andina", AN_APPROVED)
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "test-corr-1"));

        assertThat(publisher.published()).singleElement().satisfies(event -> {
            assertThat(event.data().status()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(event.correlationId()).isEqualTo("test-corr-1");
        });
    }

    @Test
    void andinaRetryWithANewNotificationIdIsAcknowledgedButNotPublishedAgain() throws Exception {
        send("andina", AN_APPROVED).andExpect(status().isOk());
        send("andina", AN_APPROVED_RETRY).andExpect(status().isOk());

        assertThat(publisher.published()).hasSize(1);
        // Both attempts can be found by their own notificacion_id (FR7).
        assertThat(store.attempts())
                .extracting(InMemoryIdempotencyStore.Attempt::providerEventId, InMemoryIdempotencyStore.Attempt::outcome)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ntf-8f1c2a77", AttemptOutcome.PUBLISHED),
                        org.assertj.core.groups.Tuple.tuple("ntf-c02d9e13", AttemptOutcome.DUPLICATE));
    }

    @Test
    void quetzalAtLeastOnceDeliveryIsPublishedOnce() throws Exception {
        send("quetzal", QZ_SUCCEEDED).andExpect(status().isOk());
        send("quetzal", QZ_SUCCEEDED).andExpect(status().isOk());
        send("quetzal", QZ_SUCCEEDED).andExpect(status().isOk());

        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    void outOfOrderNotificationsAreAllAcceptedWithTheirOwnStateChangeTime() throws Exception {
        // Reversal arrives before the approval and the pending: all three are distinct business events.
        send("andina", AN_REVERSED).andExpect(status().isOk());
        send("andina", AN_APPROVED).andExpect(status().isOk());
        send("andina", AN_PENDING).andExpect(status().isOk());
        send("quetzal", QZ_REFUNDED).andExpect(status().isOk());
        send("quetzal", QZ_SUCCEEDED).andExpect(status().isOk());

        assertThat(publisher.published())
                .extracting(e -> e.data().status())
                .containsExactly(PaymentStatus.REFUNDED, PaymentStatus.CAPTURED, PaymentStatus.PENDING,
                        PaymentStatus.REFUNDED, PaymentStatus.CAPTURED);
    }

    @Test
    void invalidSignatureIsRejectedAndNothingIsStoredOrPublished() throws Exception {
        HttpHeaders forged = TestFixtures.andinaHeaders(AN_APPROVED.body(), "2026-11-28T14:03:11-05:00", "attacker-secret");
        clock.set(AN_APPROVED.signedAt());

        send("andina", AN_APPROVED.body(), forged)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.correlationId").value("test-corr-1"));

        assertThat(publisher.published()).isEmpty();
        assertThat(store.attempts()).isEmpty();
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        clock.set(QZ_SUCCEEDED.signedAt());
        send("quetzal", QZ_SUCCEEDED.body(), new HttpHeaders()).andExpect(status().isUnauthorized());

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    void staleButOtherwiseValidRequestIsRejected() throws Exception {
        clock.set(QZ_SUCCEEDED.signedAt().plusSeconds(301));
        send("quetzal", QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers()).andExpect(status().isUnauthorized());
    }

    @Test
    void brokerOutageIsNotAcceptedAndTheProviderRetryIsPublished() throws Exception {
        doThrow(new PublishException("Service Bus unavailable", null)).when(publisher).publish(any());

        send("andina", AN_APPROVED).andExpect(status().isServiceUnavailable());
        assertThat(publisher.published()).isEmpty();

        // The provider retries (new notificacion_id): the claimed-but-unpublished event must go out now.
        doCallRealMethod().when(publisher).publish(any());
        send("andina", AN_APPROVED_RETRY).andExpect(status().isOk());

        assertThat(publisher.published()).singleElement()
                .extracting(PremiumPaymentEvent::data)
                .satisfies(d -> assertThat(d.providerEventId()).isEqualTo("ntf-c02d9e13"));
    }

    @Test
    void idempotencyStoreOutageIsNotAccepted() throws Exception {
        doThrow(new StoreUnavailableException("SQL unavailable", null)).when(store).claim(any());

        send("quetzal", QZ_SUCCEEDED).andExpect(status().isServiceUnavailable());

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    void failureToMarkAsPublishedStillAcceptsBecauseTheEventIsDurable() throws Exception {
        doThrow(new StoreUnavailableException("SQL unavailable", null)).when(store).markPublished(anyString());

        send("quetzal", QZ_SUCCEEDED).andExpect(status().isOk());

        assertThat(publisher.published()).hasSize(1);
    }

    @Test
    void unknownStateWithValidSignatureIsRejectedSoTheProviderKeepsRetrying() throws Exception {
        byte[] body = new String(AN_APPROVED.body(), StandardCharsets.UTF_8)
                .replace("\"APROBADA\"", "\"EN_DISPUTA\"").getBytes(StandardCharsets.UTF_8);
        String ts = "2026-11-28T14:03:11-05:00";
        clock.set(Instant.parse("2026-11-28T19:03:12Z"));

        send("andina", body, TestFixtures.andinaHeaders(body, ts, ANDINA_TEST_SECRET))
                .andExpect(status().isUnprocessableEntity());

        assertThat(publisher.published()).isEmpty();
    }

    @Test
    void signedButMalformedBodyIsRejected() throws Exception {
        byte[] body = "{\"notificacion_id\": \"ntf-1\", ".getBytes(StandardCharsets.UTF_8);
        String ts = "2026-11-28T14:03:11-05:00";
        clock.set(Instant.parse("2026-11-28T19:03:12Z"));

        send("andina", body, TestFixtures.andinaHeaders(body, ts, ANDINA_TEST_SECRET))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedBodyIsRejected() throws Exception {
        byte[] body = new byte[65537];
        java.util.Arrays.fill(body, (byte) ' ');
        clock.set(QZ_SUCCEEDED.signedAt());

        send("quetzal", body, QZ_SUCCEEDED.headers()).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void unknownProviderIsNotFound() throws Exception {
        send("austral", QZ_SUCCEEDED.body(), QZ_SUCCEEDED.headers()).andExpect(status().isNotFound());
    }

    @Test
    void correlationIdIsGeneratedWhenMissingOrUnsafe() throws Exception {
        clock.set(QZ_SUCCEEDED.signedAt());
        mvc.perform(post("/webhooks/quetzal").contentType(MediaType.APPLICATION_JSON)
                        .headers(QZ_SUCCEEDED.headers())
                        .header("X-Correlation-Id", "bad id\nINJECTED log line")
                        .content(QZ_SUCCEEDED.body()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("^[0-9a-f-]{36}$")));
    }

    @Test
    void personalDataNeverReachesTheLogs(CapturedOutput output) throws Exception {
        send("andina", AN_APPROVED).andExpect(status().isOk());
        send("andina", AN_APPROVED_RETRY).andExpect(status().isOk());
        send("quetzal", QZ_SUCCEEDED).andExpect(status().isOk());
        clock.set(Instant.EPOCH);
        send("andina", AN_APPROVED.body(), AN_APPROVED.headers()).andExpect(status().isUnauthorized());

        assertThat(output.getAll())
                .contains("notification accepted")
                .doesNotContain("laura.gomez@example.com", "Laura", "1020304050", "maria.lopez@example.com", "tok_qz_");
    }
}
