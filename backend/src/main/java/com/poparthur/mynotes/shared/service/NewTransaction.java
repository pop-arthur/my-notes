package com.poparthur.mynotes.shared.service;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Completes its own transaction before returning, even if a caller has an outer transaction. */
@Component
public class NewTransaction {
    private final TransactionTemplate transaction;

    public NewTransaction(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
