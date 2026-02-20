package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.eventstore.ConcurrentMapEventStore;
import de.codecentric.boot.admin.server.eventstore.OptimisticLockingException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * JDBC-backed implementation of InstanceEventStore that persists events to the database
 * while maintaining an in-memory cache for fast reads.
 */
public class JdbcEventStore extends ConcurrentMapEventStore {

	private static final Logger LOGGER = LoggerFactory.getLogger(JdbcEventStore.class);

	private final EventPersistenceStore persistenceStore;
	private final ConcurrentMap<InstanceId, List<InstanceEvent>> eventCache;

	public JdbcEventStore(final int maxLogSizePerAggregate, final EventPersistenceStore persistenceStore) {
		super(maxLogSizePerAggregate, new ConcurrentHashMap<>());
		this.persistenceStore = persistenceStore;
		this.eventCache = getEventLog();
		loadEventsFromDatabase();
	}

	/**
	 * Get the underlying event log for direct access (used by constructor).
	 */
	@SuppressWarnings("unchecked")
	private ConcurrentMap<InstanceId, List<InstanceEvent>> getEventLog() {
		try {
			final var field = ConcurrentMapEventStore.class.getDeclaredField("eventLog");
			field.setAccessible(true);
			return (ConcurrentMap<InstanceId, List<InstanceEvent>>) field.get(this);
		} catch (final Exception e) {
			LOGGER.error("Failed to access eventLog field", e);
			return new ConcurrentHashMap<>();
		}
	}

	/**
	 * Load events from database on startup.
	 */
	private void loadEventsFromDatabase() {
		LOGGER.info("Loading events from database...");
		final var events = persistenceStore.loadAll();

		if (events.isEmpty()) {
			LOGGER.info("No events found in database");
			return;
		}

		// Group events by instance ID, sorted by version
		events.stream()
			.collect(java.util.stream.Collectors.groupingBy(InstanceEvent::getInstance))
			.forEach((instanceId, instanceEvents) -> {
				instanceEvents.sort(java.util.Comparator.comparingLong(InstanceEvent::getVersion));
				eventCache.put(instanceId, Collections.synchronizedList(new java.util.ArrayList<>(instanceEvents)));
			});

		LOGGER.info("Loaded {} events for {} instances from database", events.size(), eventCache.size());

		// Log warning for instances without REGISTERED events (orphaned)
		final var orphanedCount = eventCache.entrySet().stream()
			.filter(entry -> entry.getValue().stream()
				.noneMatch(InstanceRegisteredEvent.class::isInstance))
			.count();

		if (orphanedCount > 0) {
			LOGGER.warn("Found {} instances with orphaned events - these will not receive status checks", orphanedCount);
		}
	}

	/**
	 * Publishes stored REGISTERED events to subscribers (e.g. StatusUpdateTrigger).
	 * Only REGISTERED events are published to:
	 * 1. Trigger status checks for known instances
	 * 2. Avoid spamming notifiers with historical STATUS_CHANGED events
	 * Must be called after Spring context is ready and subscribers are wired.
	 */
	public void publishStoredEvents() {
		// Only publish the latest REGISTERED event per instance
		// This triggers status checks without spamming Slack with historical events
		final var registeredEvents = eventCache.values().stream()
			.map(events -> events.stream()
				.filter(InstanceRegisteredEvent.class::isInstance)
				.reduce((first, second) -> second)  // Get latest REGISTERED event
				.orElse(null))
			.filter(Objects::nonNull)
			.toList();

		if (registeredEvents.isEmpty()) {
			LOGGER.info("No REGISTERED events to publish");
			return;
		}

		LOGGER.info("Publishing {} REGISTERED events to trigger status checks", registeredEvents.size());
		this.publish(registeredEvents);
	}

	@Override
	public Mono<Void> append(final List<InstanceEvent> events) {
		if (events.isEmpty()) {
			return Mono.empty();
		}

		return Mono.fromRunnable(() -> {
			try {
				// Append to in-memory store (validates version numbers)
				doAppend(events);

				// Persist to database asynchronously
				persistEventsAsync(events);

				// Publish events to subscribers (like StatusUpdateTrigger)
				this.publish(events);
			} catch (final OptimisticLockingException e) {
				// Version conflict - another pod may have processed this event
				handleVersionConflict(events, e);
			}
		});
	}

	/**
	 * Handle version conflicts by refreshing from database and retrying.
	 * <p>
	 * During rolling restarts, multiple pods may receive the same events and try to
	 * append them with the same version number. When this happens:
	 * 1. Reload events for the affected instance from the database
	 * 2. Update the in-memory cache with the fresh data
	 * 3. Retry appending - if it still fails, the event was already processed
	 */
	private void handleVersionConflict(final List<InstanceEvent> events, final OptimisticLockingException originalException) {
		if (events.isEmpty()) {
			return;
		}

		final var instanceId = events.getFirst().getInstance();
		LOGGER.info("Version conflict for instance {}, refreshing from database: {}",
			instanceId, originalException.getMessage());

		// Reload events from database for this instance
		final var dbEvents = persistenceStore.loadByInstanceId(instanceId);

		if (!dbEvents.isEmpty()) {
			// Update in-memory cache with database state
			eventCache.put(instanceId, Collections.synchronizedList(new java.util.ArrayList<>(dbEvents)));
			LOGGER.info("Refreshed cache for instance {} with {} events from database",
				instanceId, dbEvents.size());
		}

		// Retry appending - if it fails again, the event was likely already processed
		try {
			if (doAppend(events)) {
				// Success on retry - persist and publish
				persistEventsAsync(events);
				this.publish(events);
				LOGGER.info("Successfully appended events after cache refresh for instance {}", instanceId);
			}
		} catch (final OptimisticLockingException retryException) {
			// Event was already processed by another pod - this is expected during rolling restarts
			LOGGER.info("Event already processed for instance {} (version conflict on retry): {}",
				instanceId, retryException.getMessage());
		}
	}

	/**
	 * Persist events to database asynchronously.
	 */
	private void persistEventsAsync(final List<InstanceEvent> events) {
		Mono.fromRunnable(() -> {
			try {
				persistenceStore.saveBatch(events);
				LOGGER.debug("Persisted {} events to database", events.size());
			} catch (final Exception e) {
				LOGGER.error("Failed to persist {} events to database: {}", events.size(), e.getMessage());
			}
		}).subscribeOn(Schedulers.boundedElastic()).subscribe();
	}

	/**
	 * Reloads the in-memory cache from the database.
	 * Used after retention cleanup to evict deleted events from memory.
	 */
	public void reloadFromDatabase() {
		eventCache.clear();
		loadEventsFromDatabase();
	}

	/**
	 * Clears all events from the in-memory cache.
	 * This method is intended for testing purposes only.
	 */
	public void clearAll() {
		eventCache.clear();
		LOGGER.info("Cleared all events from in-memory cache");
	}
}
