package com.poparthur.mynotes.shared.service;

import java.util.UUID;

@FunctionalInterface
public interface UuidGenerator {
    UUID next();
}
