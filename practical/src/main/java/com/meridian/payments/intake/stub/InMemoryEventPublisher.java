package com.meridian.payments.intake.stub;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.meridian.payments.intake.canonical.PremiumPaymentEvent;
import com.meridian.payments.intake.ports.EventPublisher;

/** STUB of the Service Bus topic: keeps published events in memory so tests can inspect them. */
public class InMemoryEventPublisher implements EventPublisher {

    private final List<PremiumPaymentEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(PremiumPaymentEvent event) {
        published.add(event);
    }

    public List<PremiumPaymentEvent> published() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }
}
