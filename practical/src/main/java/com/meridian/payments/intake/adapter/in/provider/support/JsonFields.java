package com.meridian.payments.intake.adapter.in.provider.support;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.meridian.payments.intake.domain.MalformedNotificationException;

/** Strict accessors for provider payloads. Error messages contain field names only, never values. */
public final class JsonFields {

    private final ObjectReader reader;

    public JsonFields(ObjectMapper mapper) {
        // Duplicate keys are rejected: the signed bytes must have exactly one meaning.
        this.reader = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public JsonNode parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = reader.readTree(rawBody);
        } catch (IOException e) {
            throw new MalformedNotificationException("body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedNotificationException("body is not a JSON object");
        }
        return root;
    }

    public static JsonNode object(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw new MalformedNotificationException("missing object " + field);
        }
        return node;
    }

    public static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new MalformedNotificationException("missing text " + field);
        }
        return node.asText();
    }

    public static String optionalText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    public static long integer(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new MalformedNotificationException("missing integer " + field);
        }
        return node.asLong();
    }

    /** Unix seconds, or {@code null} if the field is absent or null. */
    public static Instant optionalEpochSeconds(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new MalformedNotificationException("invalid timestamp " + field);
        }
        return Instant.ofEpochSecond(node.asLong());
    }

    /** ISO-8601 with offset, converted to UTC. */
    public static Instant offsetDateTime(JsonNode parent, String field) {
        try {
            return OffsetDateTime.parse(text(parent, field)).toInstant();
        } catch (DateTimeParseException e) {
            throw new MalformedNotificationException("invalid date-time " + field);
        }
    }
}
