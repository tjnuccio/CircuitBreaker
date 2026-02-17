package org.example;

import jakarta.annotation.PreDestroy;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class CircuitBreaker {
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicInteger halfOpenRequestCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Duration resetTimeout;
    private final int failureThreshold;
    private final int halfOpenRequestLimit;

    private final ResponseEntity<Response> fallbackResponse = ResponseEntity.status(503).body(new Response("Service unavailable", 503));

    public CircuitBreaker(Duration resetTimeout, int failureThreshold, int halfOpenRequestLimit) {
        this.resetTimeout = resetTimeout;
        this.failureThreshold = failureThreshold;
        this.halfOpenRequestLimit = halfOpenRequestLimit;
    }

    public ResponseEntity<Response> execute(Supplier<ResponseEntity<Response>> action) {
        return switch (state.get()) {
            case OPEN -> fallbackResponse;
            case HALF_OPEN -> sendHalfOpenRequest(action);
            case CLOSED -> sendClosedRequest(action);
        };
    }

    private ResponseEntity<Response> sendClosedRequest(Supplier<ResponseEntity<Response>> action) {
        Objects.requireNonNull(action);

        try {
            ResponseEntity<Response> response = action.get();

            if (response.getStatusCode().is5xxServerError()) {
                incrementFailuresWhileClosed();
                return fallbackResponse;
            }

            failures.set(0);

            return response;
        } catch (Exception e) {
            incrementFailuresWhileClosed();
            return fallbackResponse;
        }
    }

    private ResponseEntity<Response> sendHalfOpenRequest(Supplier<ResponseEntity<Response>> action) {
        if (halfOpenRequestCount.incrementAndGet() > halfOpenRequestLimit) {
            return fallbackResponse;
        } else {
            try {
                ResponseEntity<Response> response = action.get();

                if (response.getStatusCode().is5xxServerError()) {
                    openCircuit(State.HALF_OPEN, List.of(halfOpenRequestCount, halfOpenSuccesses));
                    return fallbackResponse;
                }

                if (halfOpenSuccesses.incrementAndGet() >= halfOpenRequestLimit && state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    halfOpenSuccesses.set(0);
                    halfOpenRequestCount.set(0);
                }

                return response;
            } catch (Exception e) {
                openCircuit(State.HALF_OPEN, List.of(halfOpenRequestCount, halfOpenSuccesses));
                return fallbackResponse;
            }
        }
    }

    private void incrementFailuresWhileClosed() {
        int numOfFailures = failures.incrementAndGet();
        if (numOfFailures >= failureThreshold) {
            openCircuit(State.CLOSED, List.of(failures));
        }
    }

    private void openCircuit(State existingState, List<AtomicInteger> counters) {
        if (state.compareAndSet(existingState, State.OPEN)) {
            counters.forEach(c -> c.set(0));
            scheduler.schedule(
                    () -> state.compareAndSet(State.OPEN, State.HALF_OPEN),
                    resetTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    @PreDestroy
    private void shutdown() {
        scheduler.shutdown();
    }

    private enum State {
        OPEN,
        HALF_OPEN,
        CLOSED
    }
}
