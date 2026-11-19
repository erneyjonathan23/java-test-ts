package com.meridian.payments.intake.adapter.out.messaging;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.meridian.payments.intake.domain.PremiumPaymentEvent;

/** JSON wire format of the canonical event: CloudEvents attribute names, kept out of the domain. */
public final class CloudEventJson {

    private CloudEventJson() {
    }

    /** Jackson module; Spring Boot registers it in the application's ObjectMapper. */
    public static Module module() {
        return new SimpleModule("cloud-events").setMixInAnnotation(PremiumPaymentEvent.class, EventMixin.class);
    }

    abstract static class EventMixin {

        @JsonProperty("specversion")
        abstract String specVersion();

        @JsonProperty("datacontenttype")
        abstract String dataContentType();

        @JsonProperty("dataschema")
        abstract String dataSchema();

        @JsonProperty("correlationid")
        abstract String correlationId();
    }
}
