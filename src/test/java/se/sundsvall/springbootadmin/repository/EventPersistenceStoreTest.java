package se.sundsvall.springbootadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import se.sundsvall.springbootadmin.Application;

@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)
@ActiveProfiles("junit")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Sql(
	scripts = "/sql/truncate.sql",
	executionPhase = BEFORE_TEST_METHOD)
class EventPersistenceStoreTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EventPersistenceStore store;

	@Test
	void saveAndLoadEvent() {
		final var event = createRegisteredEvent("id-1", "service-1");

		store.save(event);
		final var loaded = store.loadAll();

		assertThat(loaded).hasSize(1);
		assertThat(loaded.getFirst().getInstance()).isEqualTo(event.getInstance());
	}

	@Test
	void saveBatchAndLoadEvents() {
		final var events = List.<InstanceEvent>of(
			createRegisteredEvent("id-1", "service-1"),
			createRegisteredEvent("id-2", "service-2"),
			createRegisteredEvent("id-3", "service-3"));

		store.saveBatch(events);
		final var loaded = store.loadAll();

		assertThat(loaded).hasSize(3);
	}

	@Test
	void saveBatchWithEmptyListDoesNothing() {
		store.saveBatch(List.of());
		final var loaded = store.loadAll();

		assertThat(loaded).isEmpty();
	}

	@Test
	void loadAllReturnsEventsInTimestampOrder() {
		// Create events with explicit timestamps to ensure deterministic ordering
		final var baseTime = Instant.now();
		final var event1 = createRegisteredEventWithTimestamp("id-1", "service-1", baseTime);
		final var event2 = createStatusChangedEventWithTimestamp("id-1", StatusInfo.ofUp(), baseTime.plusSeconds(1));
		final var event3 = createStatusChangedEventWithTimestamp("id-1", StatusInfo.ofDown(), baseTime.plusSeconds(2));

		// Save in reverse order to verify sorting
		store.save(event3);
		store.save(event1);
		store.save(event2);

		final var loaded = store.loadAll();

		assertThat(loaded).hasSize(3);
		// Events should be ordered by timestamp ascending
		assertThat(loaded.get(0)).isInstanceOf(InstanceRegisteredEvent.class);
		assertThat(loaded.get(1)).isInstanceOf(InstanceStatusChangedEvent.class);
		assertThat(((InstanceStatusChangedEvent) loaded.get(1)).getStatusInfo().getStatus()).isEqualTo("UP");
		assertThat(loaded.get(2)).isInstanceOf(InstanceStatusChangedEvent.class);
		assertThat(((InstanceStatusChangedEvent) loaded.get(2)).getStatusInfo().getStatus()).isEqualTo("DOWN");
	}

	@Test
	void loadAllReturnsEmptyWhenNoEvents() {
		final var loaded = store.loadAll();

		assertThat(loaded).isEmpty();
	}

	@Test
	void deleteOlderThanRemovesOldEvents() {
		// Save an event
		store.save(createRegisteredEvent("id-1", "service-1"));

		// Delete events older than 1 day in the future (should delete all)
		final var cutoff = Instant.now().plus(1, ChronoUnit.DAYS);
		final var deleted = store.deleteOlderThan(cutoff);

		assertThat(deleted).isEqualTo(1);
		assertThat(store.loadAll()).isEmpty();
	}

	@Test
	void deleteOlderThanKeepsRecentEvents() {
		// Save an event
		store.save(createRegisteredEvent("id-1", "service-1"));

		// Delete events older than 1 day in the past (should keep all)
		final var cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
		final var deleted = store.deleteOlderThan(cutoff);

		assertThat(deleted).isEqualTo(0);
		assertThat(store.loadAll()).hasSize(1);
	}

	@Test
	void deleteExcessEventsForInstanceKeepsNewestEvents() {
		final var instanceId = InstanceId.of("id-1");

		// Save 5 events for the same instance
		for (int i = 0; i < 5; i++) {
			store.save(createStatusChangedEvent("id-1", StatusInfo.ofUp()));
		}

		// Keep only 2 events
		final var deleted = store.deleteExcessEventsForInstance(instanceId, 2);

		assertThat(deleted).isEqualTo(3);
		assertThat(store.loadAll()).hasSize(2);
	}

	@Test
	void deleteExcessEventsForInstanceDoesNothingWhenBelowLimit() {
		final var instanceId = InstanceId.of("id-1");

		// Save 2 events
		store.save(createRegisteredEvent("id-1", "service-1"));
		store.save(createStatusChangedEvent("id-1", StatusInfo.ofUp()));

		// Keep 5 events (more than we have)
		final var deleted = store.deleteExcessEventsForInstance(instanceId, 5);

		assertThat(deleted).isEqualTo(0);
		assertThat(store.loadAll()).hasSize(2);
	}

	@Test
	void getDistinctInstanceIdsReturnsUniqueIds() {
		// Save events for multiple instances
		store.save(createRegisteredEvent("id-1", "service-1"));
		store.save(createStatusChangedEvent("id-1", StatusInfo.ofUp()));
		store.save(createRegisteredEvent("id-2", "service-2"));
		store.save(createRegisteredEvent("id-3", "service-3"));

		final var instanceIds = store.getDistinctInstanceIds();

		assertThat(instanceIds).hasSize(3);
		assertThat(instanceIds)
			.extracting(InstanceId::getValue)
			.containsExactlyInAnyOrder("id-1", "id-2", "id-3");
	}

	@Test
	void getDistinctInstanceIdsReturnsEmptyWhenNoEvents() {
		final var instanceIds = store.getDistinctInstanceIds();

		assertThat(instanceIds).isEmpty();
	}

	@Test
	void loadAllFiltersOutNullFromCorruptData() {
		// Insert valid event
		store.save(createRegisteredEvent("id-1", "service-1"));

		// Insert corrupt JSON directly into database
		jdbcTemplate.update(
			"INSERT INTO events (instance_id, event_type, version, timestamp, event_json) VALUES (?, ?, ?, NOW(), ?)",
			"id-corrupt", "CorruptEvent", 1L, "not valid json");

		final var loaded = store.loadAll();

		// Should only return the valid event, filtering out the corrupt one
		assertThat(loaded).hasSize(1);
		assertThat(loaded.getFirst().getInstance().getValue()).isEqualTo("id-1");
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, "http://localhost:8080").build();
		return new InstanceRegisteredEvent(instanceId, 1L, registration);
	}

	private InstanceRegisteredEvent createRegisteredEventWithTimestamp(final String id, final String name, final Instant timestamp) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, "http://localhost:8080").build();
		return new InstanceRegisteredEvent(instanceId, 1L, timestamp, registration);
	}

	private InstanceStatusChangedEvent createStatusChangedEvent(final String id, final StatusInfo statusInfo) {
		final var instanceId = InstanceId.of(id);
		return new InstanceStatusChangedEvent(instanceId, 1L, statusInfo);
	}

	private InstanceStatusChangedEvent createStatusChangedEventWithTimestamp(final String id, final StatusInfo statusInfo, final Instant timestamp) {
		final var instanceId = InstanceId.of(id);
		return new InstanceStatusChangedEvent(instanceId, 1L, timestamp, statusInfo);
	}
}
