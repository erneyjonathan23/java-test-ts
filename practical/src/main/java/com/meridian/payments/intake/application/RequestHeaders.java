package com.meridian.payments.intake.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Framework-independent, case-insensitive view of the HTTP headers a provider sent.
 * Keeps the application layer free of Spring Web types.
 */
public final class RequestHeaders {

    private final Map<String, List<String>> values;

    private RequestHeaders(Map<String, List<String>> values) {
        this.values = values;
    }

    public static RequestHeaders of(Map<String, ? extends List<String>> headers) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, list) -> copy.put(name.toLowerCase(Locale.ROOT), List.copyOf(list)));
        return new RequestHeaders(copy);
    }

    /** First value of the header, or {@code null} if absent. */
    public String first(String name) {
        List<String> list = values.get(name);
        return list == null || list.isEmpty() ? null : list.get(0);
    }
}
