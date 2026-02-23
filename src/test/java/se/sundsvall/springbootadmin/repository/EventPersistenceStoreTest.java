package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import se.sundsvall.springbootadmin.Application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

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
		final var event = createRegisteredEvent(UUID.randomUUID().toString(), "service-1");

		store.save(event);
		final var loaded = store.loadAll();

		assertThat(loaded).hasSize(1);
		assertThat(loaded.getFirst().getInstance()).isEqualTo(event.getInstance());
	}

	@Test
	void saveBatchAndLoadEvents() {
		final var events = List.<InstanceEvent>of(
			createRegisteredEvent(UUID.randomUUID().toString(), "service-1"),
			createRegisteredEvent(UUID.randomUUID().toString(), "service-2"),
			createRegisteredEvent(UUID.randomUUID().toString(), "service-3"));

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
	void loadAllReturnsEventsOrderedByInstanceIdAndVersion() {
		// Create events with explicit timestamps to ensure correct ordering
		// Each event for the same instance must have a unique version number
		final var baseTime = Instant.now();
		final var uniqueId = UUID.randomUUID().toString();
		final var event1 = createRegisteredEventWithTimestamp(uniqueId, "service-1", 1L, baseTime);
		final var event2 = createStatusChangedEventWithTimestamp(uniqueId, StatusInfo.ofUp(), 2L, baseTime.plusSeconds(1));
		final var event3 = createStatusChangedEventWithTimestamp(uniqueId, StatusInfo.ofDown(), 3L, baseTime.plusSeconds(2));

		// Save in reverse order to verify sorting
		store.save(event3);
		store.save(event1);
		store.save(event2);

		final var loaded = store.loadAll();

		assertThat(loaded).hasSize(3);
		// Events should be ordered by version ascending (all same instance_id)
		assertThat(loaded.get(0).getVersion()).isEqualTo(1L);
		assertThat(loaded.get(1).getVersion()).isEqualTo(2L);
		assertThat(loaded.get(2).getVersion()).isEqualTo(3L);
	}

	@Test
	void loadAllReturnsEmptyWhenNoEvents() {
		final var loaded = store.loadAll();

		assertThat(loaded).isEmpty();
	}

	@Test
	void deleteOlderThanRemovesOldEvents() {
		// Save an event
		store.save(createRegisteredEvent(UUID.randomUUID().toString(), "service-1"));

		// Delete events older than 1 day in the future (should delete all)
		final var cutoff = Instant.now().plus(1, ChronoUnit.DAYS);
		final var deleted = store.deleteOlderThan(cutoff);

		assertThat(deleted).isOne();
		assertThat(store.loadAll()).isEmpty();
	}

	@Test
	void deleteOlderThanKeepsRecentEvents() {
		// Save an event
		store.save(createRegisteredEvent(UUID.randomUUID().toString(), "service-1"));

		// Delete events older than 1 day in the past (should keep all)
		final var cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
		final var deleted = store.deleteOlderThan(cutoff);

		assertThat(deleted).isZero();
		assertThat(store.loadAll()).hasSize(1);
	}

	@Test
	void deleteExcessEventsForInstanceKeepsNewestEvents() {
		final var uniqueId = UUID.randomUUID().toString();
		final var instanceId = InstanceId.of(uniqueId);

		// Save 5 events for the same instance with unique version numbers
		for (var i = 1; i <= 5; i++) {
			store.save(createStatusChangedEvent(uniqueId, StatusInfo.ofUp(), i));
		}

		// Keep only 2 events
		final var deleted = store.deleteExcessEventsForInstance(instanceId, 2);

		assertThat(deleted).isEqualTo(3);
		assertThat(store.loadAll()).hasSize(2);
	}

	@Test
	void deleteExcessEventsForInstanceDoesNothingWhenBelowLimit() {
		final var uniqueId = UUID.randomUUID().toString();
		final var instanceId = InstanceId.of(uniqueId);

		// Save 2 events with unique version numbers
		store.save(createRegisteredEvent(uniqueId, "service-1", 1L));
		store.save(createStatusChangedEvent(uniqueId, StatusInfo.ofUp(), 2L));

		// Keep 5 events (more than we have)
		final var deleted = store.deleteExcessEventsForInstance(instanceId, 5);

		assertThat(deleted).isZero();
		assertThat(store.loadAll()).hasSize(2);
	}

	@Test
	void getDistinctInstanceIdsReturnsUniqueIds() {
		// Save events for multiple instances with unique version numbers per instance
		final var uniqueId1 = UUID.randomUUID().toString();
		final var uniqueId2 = UUID.randomUUID().toString();
		final var uniqueId3 = UUID.randomUUID().toString();
		store.save(createRegisteredEvent(uniqueId1, "service-1", 1L));
		store.save(createStatusChangedEvent(uniqueId1, StatusInfo.ofUp(), 2L));
		store.save(createRegisteredEvent(uniqueId2, "service-2", 1L));
		store.save(createRegisteredEvent(uniqueId3, "service-3", 1L));

		final var instanceIds = store.getDistinctInstanceIds();

		assertThat(instanceIds).hasSize(3);
		assertThat(instanceIds)
			.extracting(InstanceId::getValue)
			.containsExactlyInAnyOrder(uniqueId1, uniqueId2, uniqueId3);
	}

	@Test
	void getDistinctInstanceIdsReturnsEmptyWhenNoEvents() {
		final var instanceIds = store.getDistinctInstanceIds();

		assertThat(instanceIds).isEmpty();
	}

	@Test
	void loadByInstanceIdReturnsEventsForInstance() {
		final var uniqueId1 = UUID.randomUUID().toString();
		final var uniqueId2 = UUID.randomUUID().toString();
		// Save events for multiple instances
		store.save(createRegisteredEvent(uniqueId1, "service-1", 1L));
		store.save(createStatusChangedEvent(uniqueId1, StatusInfo.ofUp(), 2L));
		store.save(createStatusChangedEvent(uniqueId1, StatusInfo.ofDown(), 3L));
		store.save(createRegisteredEvent(uniqueId2, "service-2", 1L));

		final var events = store.loadByInstanceId(InstanceId.of(uniqueId1));

		assertThat(events).hasSize(3);
		// Events should be ordered by version ascending
		assertThat(events.get(0).getVersion()).isEqualTo(1L);
		assertThat(events.get(1).getVersion()).isEqualTo(2L);
		assertThat(events.get(2).getVersion()).isEqualTo(3L);
	}

	@Test
	void loadByInstanceIdReturnsEmptyForUnknownInstance() {
		final var events = store.loadByInstanceId(InstanceId.of("unknown"));

		assertThat(events).isEmpty();
	}

	@Test
	void saveBatchHandlesDuplicateKeyGracefully() {
		final var uniqueId = UUID.randomUUID().toString();
		// Save an event
		store.save(createRegisteredEvent(uniqueId, "service-1", 1L));

		// Try to save the same event again via batch (same instance_id + version)
		final var duplicateEvents = List.<InstanceEvent>of(
			createRegisteredEvent(uniqueId, "service-1", 1L));

		// Should not throw - duplicate key exception should be handled gracefully
		store.saveBatch(duplicateEvents);

		// Original event should still be there
		final var loaded = store.loadAll();
		assertThat(loaded).hasSize(1);
	}

	@Test
	void saveBatchWithDuplicatesPersistsNonDuplicates() {
		final var uniqueId1 = UUID.randomUUID().toString();
		final var uniqueId2 = UUID.randomUUID().toString();

		// Save one event directly
		store.save(createRegisteredEvent(uniqueId1, "service-1", 1L));

		// Batch contains the duplicate + a new event
		final var events = List.<InstanceEvent>of(
			createRegisteredEvent(uniqueId1, "service-1", 1L),
			createRegisteredEvent(uniqueId2, "service-2", 1L));

		store.saveBatch(events);

		final var loaded = store.loadAll();
		assertThat(loaded).hasSize(2);
	}

	@Test
	void loadAllFiltersOutNullFromCorruptData() {
		final var uniqueId = UUID.randomUUID().toString();
		// Insert valid event
		store.save(createRegisteredEvent(uniqueId, "service-1"));

		// Insert corrupt JSON directly into database
		jdbcTemplate.update(
			"INSERT INTO event (instance_id, event_type, version, timestamp, event_json) VALUES (?, ?, ?, NOW(), ?)",
			"id-corrupt", "CorruptEvent", 1L, "not valid json");

		final var loaded = store.loadAll();

		// Should only return the valid event, filtering out the corrupt one
		assertThat(loaded).hasSize(1);
		assertThat(loaded.getFirst().getInstance().getValue()).isEqualTo(uniqueId);
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		return createRegisteredEvent(id, name, 1L);
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name, final long version) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, "http://localhost:8080").build();
		return new InstanceRegisteredEvent(instanceId, version, registration);
	}

	private InstanceRegisteredEvent createRegisteredEventWithTimestamp(final String id, final String name, final long version, final Instant timestamp) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, "http://localhost:8080").build();
		return new InstanceRegisteredEvent(instanceId, version, timestamp, registration);
	}

	private InstanceStatusChangedEvent createStatusChangedEvent(final String id, final StatusInfo statusInfo, final long version) {
		final var instanceId = InstanceId.of(id);
		return new InstanceStatusChangedEvent(instanceId, version, statusInfo);
	}

	private InstanceStatusChangedEvent createStatusChangedEventWithTimestamp(final String id, final StatusInfo statusInfo, final long version, final Instant timestamp) {
		final var instanceId = InstanceId.of(id);
		return new InstanceStatusChangedEvent(instanceId, version, timestamp, statusInfo);
	}
}
