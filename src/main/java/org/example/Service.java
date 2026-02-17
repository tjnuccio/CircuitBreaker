package org.example;

import java.time.Duration;

public class Service {
    public void callDownstream() {
        CircuitBreaker cb = new CircuitBreaker(Duration.ofSeconds(5), 3, 5);
        Downstream downstream = new Downstream();

        cb.execute(downstream::create);
    }
}
