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
import org.junit.jupiter.api.Test;
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
@Sql(scripts = "/sql/truncate.sql", executionPhase = BEFORE_TEST_METHOD)
class EventPersistenceStoreTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EventPersistenceStore store;

	@Test
	void saveBatchPersistsEvents() {
		final var events = List.<InstanceEvent>of(
			registeredEvent(UUID.randomUUID().toString(), "service-1"),
			registeredEvent(UUID.randomUUID().toString(), "service-2"),
			registeredEvent(UUID.randomUUID().toString(), "service-3"));

		store.saveBatch(events);

		assertThat(rowCount()).isEqualTo(3);
	}

	@Test
	void saveBatchWithEmptyListIsNoOp() {
		store.saveBatch(List.of());
		assertThat(rowCount()).isZero();
	}

	@Test
	void saveBatchSwallowsDuplicateKey() {
		final var id = UUID.randomUUID().toString();
		final var event = registeredEvent(id, "service-1", 1L);

		store.saveBatch(List.of(event));
		store.saveBatch(List.of(event));

		assertThat(rowCount()).isOne();
	}

	@Test
	void saveBatchPersistsNonDuplicatesEvenWhenSomeCollide() {
		final var id1 = UUID.randomUUID().toString();
		final var id2 = UUID.randomUUID().toString();

		store.saveBatch(List.of(registeredEvent(id1, "service-1", 1L)));
		store.saveBatch(List.of(
			registeredEvent(id1, "service-1", 1L),
			registeredEvent(id2, "service-2", 1L)));

		assertThat(rowCount()).isEqualTo(2);
	}

	@Test
	void deleteOlderThanRemovesOldEvents() {
		store.saveBatch(List.of(registeredEvent(UUID.randomUUID().toString(), "service-1")));

		final var deleted = store.deleteOlderThan(Instant.now().plus(1, ChronoUnit.DAYS));

		assertThat(deleted).isOne();
		assertThat(rowCount()).isZero();
	}

	@Test
	void deleteOlderThanKeepsRecentEvents() {
		store.saveBatch(List.of(registeredEvent(UUID.randomUUID().toString(), "service-1")));

		final var deleted = store.deleteOlderThan(Instant.now().minus(1, ChronoUnit.DAYS));

		assertThat(deleted).isZero();
		assertThat(rowCount()).isOne();
	}

	@Test
	void deleteExcessEventsForInstanceKeepsNewest() {
		final var id = UUID.randomUUID().toString();
		final var instanceId = InstanceId.of(id);
		for (var i = 1; i <= 5; i++) {
			store.saveBatch(List.of(statusChangedEvent(id, StatusInfo.ofUp(), i)));
		}

		final var deleted = store.deleteExcessEventsForInstance(instanceId, 2);

		assertThat(deleted).isEqualTo(3);
		assertThat(rowCount()).isEqualTo(2);
	}

	@Test
	void deleteExcessEventsDoesNothingWhenBelowLimit() {
		final var id = UUID.randomUUID().toString();
		final var instanceId = InstanceId.of(id);
		store.saveBatch(List.of(
			registeredEvent(id, "service-1", 1L),
			statusChangedEvent(id, StatusInfo.ofUp(), 2L)));

		final var deleted = store.deleteExcessEventsForInstance(instanceId, 5);

		assertThat(deleted).isZero();
		assertThat(rowCount()).isEqualTo(2);
	}

	@Test
	void getDistinctInstanceIdsReturnsUniqueIds() {
		final var id1 = UUID.randomUUID().toString();
		final var id2 = UUID.randomUUID().toString();
		final var id3 = UUID.randomUUID().toString();
		store.saveBatch(List.of(
			registeredEvent(id1, "service-1", 1L),
			statusChangedEvent(id1, StatusInfo.ofUp(), 2L),
			registeredEvent(id2, "service-2", 1L),
			registeredEvent(id3, "service-3", 1L)));

		final var instanceIds = store.getDistinctInstanceIds();

		assertThat(instanceIds)
			.extracting(InstanceId::getValue)
			.containsExactlyInAnyOrder(id1, id2, id3);
	}

	@Test
	void getDistinctInstanceIdsReturnsEmptyWhenNoEvents() {
		assertThat(store.getDistinctInstanceIds()).isEmpty();
	}

	private int rowCount() {
		final var count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event", Integer.class);
		return count == null ? 0 : count;
	}

	private static InstanceRegisteredEvent registeredEvent(final String id, final String name) {
		return registeredEvent(id, name, 1L);
	}

	private static InstanceRegisteredEvent registeredEvent(final String id, final String name, final long version) {
		return new InstanceRegisteredEvent(InstanceId.of(id), version, Registration.create(name, "http://localhost:8080").build());
	}

	private static InstanceStatusChangedEvent statusChangedEvent(final String id, final StatusInfo statusInfo, final long version) {
		return new InstanceStatusChangedEvent(InstanceId.of(id), version, statusInfo);
	}
}
