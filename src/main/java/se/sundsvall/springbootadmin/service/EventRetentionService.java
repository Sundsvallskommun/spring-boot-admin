package se.sundsvall.springbootadmin.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.sundsvall.dept44.scheduling.Dept44Scheduled;
import se.sundsvall.springbootadmin.configuration.EventJournalProperties;
import se.sundsvall.springbootadmin.repository.EventPersistenceStore;

/**
 * Periodic cleanup of the audit log. Trims events older than the retention period and caps the number of events
 * kept per instance so the table cannot grow unbounded for a flapping service.
 * <p>
 * Cluster-coordinated via {@code @Dept44Scheduled} (ShedLock), so only one SBA replica performs the cleanup.
 */
@Service
public class EventRetentionService {

	private static final Logger LOGGER = LoggerFactory.getLogger(EventRetentionService.class);

	private final EventPersistenceStore persistenceStore;
	private final EventJournalProperties properties;

	public EventRetentionService(final EventPersistenceStore persistenceStore, final EventJournalProperties properties) {
		this.persistenceStore = persistenceStore;
		this.properties = properties;
	}

	@Dept44Scheduled(
		cron = "${scheduler.journal.retention.cron.expression}",
		name = "${scheduler.journal.retention.name}",
		lockAtMostFor = "${scheduler.journal.retention.lock-at-most-for}",
		maximumExecutionTime = "${scheduler.journal.retention.maximum-execution-time}")
	public void cleanup() {
		LOGGER.info("Starting audit log retention cleanup");

		final var cutoff = Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS);
		persistenceStore.deleteOlderThan(cutoff);

		final var instanceIds = persistenceStore.getDistinctInstanceIds();
		var totalDeletedByCount = 0;
		for (final var instanceId : instanceIds) {
			totalDeletedByCount += persistenceStore.deleteExcessEventsForInstance(instanceId, properties.maxEventsPerInstance());
		}
		if (totalDeletedByCount > 0) {
			LOGGER.info("Deleted {} excess events (max {} per instance)", totalDeletedByCount, properties.maxEventsPerInstance());
		}

		LOGGER.info("Audit log retention cleanup completed");
	}
}
