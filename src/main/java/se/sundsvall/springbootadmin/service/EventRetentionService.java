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
 * Service responsible for cleaning up old events based on retention policy.
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

	/**
	 * Scheduled cleanup job that removes old events and enforces max events per instance.
	 */
	@Dept44Scheduled(
		cron = "${scheduler.journal.retention.cron.expression}",
		name = "${scheduler.journal.retention.name}",
		lockAtMostFor = "${scheduler.journal.retention.lock-at-most-for}",
		maximumExecutionTime = "${scheduler.journal.retention.maximum-execution-time}")
	public void cleanup() {
		LOGGER.info("Starting event retention cleanup");

		// Delete events older than retention period
		final var cutoff = Instant.now().minus(properties.retentionDays(), ChronoUnit.DAYS);
		final var deletedByAge = persistenceStore.deleteOlderThan(cutoff);
		LOGGER.info("Deleted {} events older than {} days", deletedByAge, properties.retentionDays());

		// Delete excess events per instance
		final var instanceIds = persistenceStore.getDistinctInstanceIds();
		var totalDeletedByCount = 0;

		for (final var instanceId : instanceIds) {
			final var deleted = persistenceStore.deleteExcessEventsForInstance(instanceId, properties.maxEventsPerInstance());
			totalDeletedByCount += deleted;
		}

		if (totalDeletedByCount > 0) {
			LOGGER.info("Deleted {} excess events (max {} per instance)", totalDeletedByCount, properties.maxEventsPerInstance());
		}

		LOGGER.info("Event retention cleanup completed");
	}
}
