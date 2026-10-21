package com.tempoapi.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class AfterCommit {
    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() { }

    public static void run(Runnable notification) {
        Runnable guarded = () -> {
            try {
                notification.run();
            } catch (RuntimeException ex) {
                // A failed notification cannot undo committed database state.
                log.warn("Post-commit notification failed ({})", ex.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    guarded.run();
                }
            });
        } else {
            guarded.run();
        }
    }
}
