package com.jdoan.inventory.graphql.soapclient;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts SOAP calls, per operation.
 *
 * WHY THIS EXISTS: the N+1 problem is the first thing anyone mentions about
 * GraphQL and the last thing anyone measures. A README claiming "batching fixed
 * it" is worth nothing; a counter that says 7 before and 2 after is worth
 * something. This is the only reason the number in the README can be trusted.
 */
@Component
public class BackendCallCounter {

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    public void record(String operation) {
        counts.computeIfAbsent(operation, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        return counts.entrySet().stream().collect(
                java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    public long total() {
        return counts.values().stream().mapToLong(AtomicLong::get).sum();
    }

    public void reset() {
        counts.clear();
    }
}
