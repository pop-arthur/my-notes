package com.poparthur.mynotes.shared.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

@Component
public class ServiceTime {
    private final Clock clock;

    public ServiceTime(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
