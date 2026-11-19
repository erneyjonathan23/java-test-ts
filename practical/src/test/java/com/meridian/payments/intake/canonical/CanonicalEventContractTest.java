package com.meridian.payments.intake.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meridian.payments.intake.TestFixtures;
import com.meridian.payments.intake.TestFixtures.Vector;
import com.meridian.payments.intake.provider.ProviderAdapter;
import com.meridian.payments.intake.provider.andina.AndinaAdapter;
import com.meridian.payments.intake.provider.quetzal.QuetzalAdapter;
import com.meridian.payments.intake.provider.support.PolicyReferenceNormalizer;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Contract test: every canonical event produced from the providers' samples must validate against
 * docs/api/events/premium-payment.schema.json and must not contain payer personal data.
 */
class CanonicalEventContractTest {

    /** Personal data present in the samples; none of it may leave the adapter. */
    private static final List<String> PERSONAL_DATA = List.of(
            "maria.lopez@example.com", "jorge.ramirez@example.com",
            "Laura", "Gómez", "1020304050", "laura.gomez@example.com",
            "tok_qz_");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final JsonSchema SCHEMA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(new String(TestFixtures.read(TestFixtures.EVENT_SCHEMA), java.nio.charset.StandardCharsets.UTF_8),
                    SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());

    private static final ProviderAdapter QUETZAL = new QuetzalAdapter(
            TestFixtures.secrets("unused"), new ObjectMapper(), new PolicyReferenceNormalizer());
    private static final ProviderAdapter ANDINA = new AndinaAdapter(
            TestFixtures.secrets("unused"), new ObjectMapper(), new PolicyReferenceNormalizer());

    static Stream<Arguments> samples() {
        return Stream.concat(
                TestFixtures.ALL_QUETZAL.stream().map(v -> Arguments.of(v.file(), QUETZAL, v)),
                TestFixtures.ALL_ANDINA.stream().map(v -> Arguments.of(v.file(), ANDINA, v)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void eventFromEverySampleConformsToTheSchemaAndHasNoPersonalData(String file, ProviderAdapter adapter, Vector vector)
            throws Exception {
        PremiumPaymentEvent event = PremiumPaymentEvent.wrap(
                adapter.toCanonical(vector.body()), adapter.id(), Instant.parse("2026-11-28T19:03:12Z"), "corr-123");
        String json = MAPPER.writeValueAsString(event);

        Set<ValidationMessage> errors = SCHEMA.validate(MAPPER.readTree(json));
        assertThat(errors).as(json).isEmpty();
        assertThat(PERSONAL_DATA).allSatisfy(pii -> assertThat(json).doesNotContain(pii));
    }

    @Test
    void envelopeUsesCloudEventsNamesAndADeterministicId() throws Exception {
        PremiumPaymentData data = ANDINA.toCanonical(TestFixtures.AN_APPROVED.body());
        PremiumPaymentData retry = ANDINA.toCanonical(TestFixtures.AN_APPROVED_RETRY.body());
        PremiumPaymentEvent first = PremiumPaymentEvent.wrap(data, "andina", Instant.parse("2026-11-28T19:03:12Z"), "a");
        PremiumPaymentEvent second = PremiumPaymentEvent.wrap(retry, "andina", Instant.parse("2026-11-28T19:08:12Z"), "b");

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(first));
        assertThat(json.get("specversion").asText()).isEqualTo("1.0");
        assertThat(json.get("dataschema").asText()).endsWith("/premium-payment/1.0");
        assertThat(json.get("correlationid").asText()).isEqualTo("a");
        assertThat(json.get("subject").asText()).isEqualTo("ANDINA:TRX-CO-5521907");
        assertThat(json.at("/data/statusChangedAt").asText()).isEqualTo("2026-11-28T19:03:09Z");
        // Same business event => same event id, whichever attempt produced it.
        assertThat(second.id()).isEqualTo(first.id());
    }

    @Test
    void schemaRejectsAFloatingPointAmount() throws Exception {
        PremiumPaymentEvent event = PremiumPaymentEvent.wrap(
                QUETZAL.toCanonical(TestFixtures.QZ_SUCCEEDED.body()), "quetzal", Instant.now(), "c");
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(event));
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.at("/data/amount")).put("value", 1250.0);

        assertThat(SCHEMA.validate(json)).isNotEmpty();
    }
}
