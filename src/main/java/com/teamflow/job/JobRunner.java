package com.teamflow.job;

import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JobRunner {
    private final JobStore store;
    private final JobDelivery delivery;
    public JobRunner(JobStore store, JobDelivery delivery) { this.store = store; this.delivery = delivery; }

    public void runBatch() {
        for (int i = 0; i < 20; i++) {
            var claim = store.claim();
            if (claim == null) return;
            try { delivery.deliver(claim); }
            catch (RuntimeException exception) {
                store.failed(claim, exception);
                LoggerFactory.getLogger(JobRunner.class).warn("Background job {} failed; retry state persisted.", claim.id());
            }
        }
    }
}
