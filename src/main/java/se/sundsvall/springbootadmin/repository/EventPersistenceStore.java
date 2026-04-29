package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Write-only persistence for the SBA event audit log.
 * <p>
 * Uses JdbcTemplate rather than JPA/Hibernate because {@link InstanceEvent} and {@link InstanceId} are SBA library
 * types that cannot be annotated as JPA entities. Events are stored as JSON blobs, so ORM provides no benefit.
 */
@Service
public class EventPersistenceStore {

	private static final Logger LOGGER = LoggerFactory.getLogger(EventPersistenceStore.class);

	/**
	 * INSERT IGNORE makes a row that violates {@code uk_instance_version} a silent no-op at the DB level rather
	 * than raising a duplicate-key error. Both SBA replicas observe the same Hazelcast event stream and would
	 * otherwise both insert the same row, producing noisy JDBC warnings.
	 */
	private static final String INSERT_SQL = """
		INSERT IGNORE INTO event (instance_id, event_type, version, timestamp, event_json)
		VALUES (?, ?, ?, ?, ?)
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

	public void saveBatch(@NonNull final List<InstanceEvent> events) {
		if (events.isEmpty()) {
			return;
		}

		jdbc.batchUpdate(INSERT_SQL, events, events.size(), (ps, event) -> {
			ps.setString(1, event.getInstance().getValue());
			ps.setString(2, event.getClass().getSimpleName());
			ps.setLong(3, event.getVersion());
			ps.setTimestamp(4, Timestamp.from(event.getTimestamp()));
			ps.setString(5, serializer.serialize(event));
		});
		LOGGER.debug("Persisted {} audit events", events.size());
	}

	public int deleteOlderThan(@NonNull final Instant cutoff) {
		final var deleted = jdbc.update(DELETE_OLDER_THAN_SQL, Timestamp.from(cutoff));
		LOGGER.info("Deleted {} events older than {}", deleted, cutoff);
		return deleted;
	}

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

	public List<InstanceId> getDistinctInstanceIds() {
		return jdbc.query(SELECT_DISTINCT_INSTANCE_IDS_SQL,
			(rs, _) -> InstanceId.of(rs.getString("instance_id")));
	}
}
