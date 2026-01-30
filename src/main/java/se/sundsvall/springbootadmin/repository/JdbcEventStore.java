package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.eventstore.ConcurrentMapEventStore;
import java.util.List;
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
				eventCache.put(instanceId, new java.util.ArrayList<>(instanceEvents));
			});

		LOGGER.info("Loaded {} events for {} instances from database", events.size(), eventCache.size());
	}

	/**
	 * Publishes all stored events to subscribers (e.g. StatusUpdateTrigger).
	 * Must be called after Spring context is ready and subscribers are wired.
	 */
	public void publishStoredEvents() {
		final var allEvents = eventCache.values().stream()
			.flatMap(List::stream)
			.toList();

		if (allEvents.isEmpty()) {
			return;
		}

		LOGGER.info("Publishing {} stored events to trigger status checks", allEvents.size());
		this.publish(allEvents);
	}

	@Override
	public Mono<Void> append(final List<InstanceEvent> events) {
		if (events.isEmpty()) {
			return Mono.empty();
		}

		return Mono.fromRunnable(() -> {
			// Append to in-memory store (validates version numbers)
			while (!doAppend(events)) {
				Thread.onSpinWait(); // Retry until successful (handles optimistic locking)
			}

			// Persist to database asynchronously
			persistEventsAsync(events);

			// Publish events to subscribers (like StatusUpdateTrigger)
			this.publish(events);
		});
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
	 * Clears all events from the in-memory cache.
	 * This method is intended for testing purposes only.
	 */
	public void clearAll() {
		eventCache.clear();
		LOGGER.info("Cleared all events from in-memory cache");
	}
}
