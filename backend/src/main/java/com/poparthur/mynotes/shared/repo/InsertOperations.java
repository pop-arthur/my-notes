package com.poparthur.mynotes.shared.repo;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityExistsException;
import java.sql.SQLException;
import java.util.function.Function;

/** Insert-only: persist instead of merge so an existing ID is never overwritten. */
public final class InsertOperations {
    private InsertOperations() {
    }

    public static void insert(EntityManager em, Object entity, Function<Throwable, RuntimeException> duplicate) {
        if (em.contains(entity)) {
            throw duplicate.apply(new EntityExistsException("Entity is already managed"));
        }
        try {
            em.persist(entity);
            em.flush();
        } catch (RuntimeException failure) {
            if (failure instanceof EntityExistsException) {
                throw duplicate.apply(failure);
            }
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    throw duplicate.apply(failure);
                }
            }
            throw failure;
        }
    }
}
