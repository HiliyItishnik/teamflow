package com.teamflow.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "teamflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduler {
    private final JobRunner runner;
    public JobScheduler(JobRunner runner) { this.runner = runner; }
    @Scheduled(initialDelay = 2000, fixedDelay = 2000)
    public void poll() { runner.runBatch(); }
}
