package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Handles database operations for InstanceEvent persistence.
 * <p>
 * Uses JdbcTemplate rather than JPA/Hibernate because the persisted domain objects
 * ({@link InstanceEvent}, {@link InstanceId}) are Spring Boot Admin library types that
 * cannot be annotated as JPA entities. Events are stored as serialized JSON blobs,
 * which removes any benefit from object-relational mapping. The retention queries
 * (e.g. delete excess events per instance with subquery/LIMIT) would require native
 * SQL in a Spring Data repository regardless, making JdbcTemplate the simpler choice.
 */
public class EventPersistenceStore {

	private static final Logger LOGGER = LoggerFactory.getLogger(EventPersistenceStore.class);

	private static final String INSERT_SQL = """
		INSERT INTO event (instance_id, event_type, version, timestamp, event_json)
		VALUES (?, ?, ?, ?, ?)
		""";

	private static final String SELECT_ALL_SQL = """
		SELECT event_json FROM event ORDER BY timestamp ASC
		""";

	private static final String DELETE_OLDER_THAN_SQL = """
		DELETE FROM event WHERE timestamp < ?
		""";

	private static final String DELETE_EXCESS_EVENTS_SQL = """
		DELETE FROM event
		WHERE instance_id = ?
		AND id NOT IN (
		    SELECT id FROM (
		        SELECT id FROM event
		        WHERE instance_id = ?
		        ORDER BY timestamp DESC
		        LIMIT ?
		    ) AS keep
		)
		""";

	private static final String SELECT_DISTINCT_INSTANCE_IDS_SQL = """
		SELECT DISTINCT instance_id FROM event
		""";

	private final JdbcTemplate jdbc;
	private final EventSerializer serializer;

	public EventPersistenceStore(final JdbcTemplate jdbc, final EventSerializer serializer) {
		this.jdbc = jdbc;
		this.serializer = serializer;
	}

	/**
	 * Save a single event to the database.
	 *
	 * @param event the event to persist
	 */
	public void save(@NonNull final InstanceEvent event) {
		final var json = serializer.serialize(event);
		final var eventType = event.getClass().getSimpleName();

		jdbc.update(INSERT_SQL,
			event.getInstance().getValue(),
			eventType,
			event.getVersion(),
			Timestamp.from(event.getTimestamp()),
			json);

		LOGGER.info("Persisted event: {} for instance: {}", eventType, event.getInstance());
	}

	/**
	 * Save multiple events in a batch.
	 *
	 * @param events the events to persist
	 */
	public void saveBatch(@NonNull final List<InstanceEvent> events) {
		if (events.isEmpty()) {
			return;
		}

		jdbc.batchUpdate(INSERT_SQL, events, events.size(), (ps, event) -> {
			final var json = serializer.serialize(event);
			final var eventType = event.getClass().getSimpleName();
			ps.setString(1, event.getInstance().getValue());
			ps.setString(2, eventType);
			ps.setLong(3, event.getVersion());
			ps.setTimestamp(4, Timestamp.from(event.getTimestamp()));
			ps.setString(5, json);
		});

		LOGGER.debug("Batch persisted {} events", events.size());
	}

	/**
	 * Load all events from the database ordered by timestamp ascending.
	 *
	 * @return list of all persisted events (excludes null from failed deserializations)
	 */
	public List<InstanceEvent> loadAll() {
		try {
			final var events = jdbc.query(
				SELECT_ALL_SQL,
				(rs, _) -> serializer.deserialize(rs.getString("event_json")));

			final var validEvents = events.stream()
				.filter(Objects::nonNull)
				.toList();

			LOGGER.info("Loaded {} events from database", validEvents.size());
			return validEvents;

		} catch (final Exception e) {
			LOGGER.warn("Could not load events from database (may be first run): {}", e.getMessage());
			return List.of();
		}
	}

	/**
	 * Delete events older than the specified cutoff time.
	 *
	 * @param  cutoff the cutoff time - events older than this will be deleted
	 * @return        the number of deleted events
	 */
	public int deleteOlderThan(@NonNull final Instant cutoff) {
		final var deleted = jdbc.update(DELETE_OLDER_THAN_SQL, Timestamp.from(cutoff));
		LOGGER.info("Deleted {} events older than {}", deleted, cutoff);
		return deleted;
	}

	/**
	 * Delete excess events for an instance, keeping only the newest N events.
	 *
	 * @param  instanceId the instance ID
	 * @param  maxEvents  the maximum number of events to keep
	 * @return            the number of deleted events
	 */
	public int deleteExcessEventsForInstance(@NonNull final InstanceId instanceId, final int maxEvents) {
		final var deleted = jdbc.update(DELETE_EXCESS_EVENTS_SQL,
			instanceId.getValue(),
			instanceId.getValue(),
			maxEvents);

		if (deleted > 0) {
			LOGGER.info("Deleted {} excess events for instance: {}", deleted, instanceId);
		}
		return deleted;
	}

	/**
	 * Get all distinct instance IDs that have events.
	 *
	 * @return list of distinct instance IDs
	 */
	public List<InstanceId> getDistinctInstanceIds() {
		return jdbc.query(SELECT_DISTINCT_INSTANCE_IDS_SQL,
			(rs, _) -> InstanceId.of(rs.getString("instance_id")));
	}
}
