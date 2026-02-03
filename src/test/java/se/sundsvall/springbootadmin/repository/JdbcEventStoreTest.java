package se.sundsvall.springbootadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.test.context.jdbc.Sql.ExecutionPhase.BEFORE_TEST_METHOD;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import reactor.test.StepVerifier;
import se.sundsvall.springbootadmin.Application;

@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)
@ActiveProfiles("junit")
@Sql(
	scripts = "/sql/truncate.sql",
	executionPhase = BEFORE_TEST_METHOD)
class JdbcEventStoreTest {

	@Autowired
	private JdbcEventStore eventStore;

	private static final String FAKE_URL = "http://localhost:8080";

	@BeforeEach
	void setUp() {
		// Ensure store is empty before each test as this has been an issue in the past
		eventStore.clearAll();
	}

	@Test
	void appendAndFindEvents() {
		final var uniqueId1 = UUID.randomUUID().toString();
		final var uniqueId2 = UUID.randomUUID().toString();

		// Events must be grouped by instance for append (ConcurrentMapEventStore requirement)
		final var events1 = List.<InstanceEvent>of(createRegisteredEvent(uniqueId1, "service-1"));
		final var events2 = List.<InstanceEvent>of(createRegisteredEvent(uniqueId2, "service-2"));

		StepVerifier.create(eventStore.append(events1).then(eventStore.append(events2)))
			.verifyComplete();

		// Use find() and check first event is our registration (background StatusUpdateTrigger may add more)
		StepVerifier.create(eventStore.find(InstanceId.of(uniqueId1)).collectList())
			.assertNext(events -> {
				assertThat(events).isNotEmpty();
				assertThat(events.getFirst()).isInstanceOf(InstanceRegisteredEvent.class);
			})
			.verifyComplete();

		StepVerifier.create(eventStore.find(InstanceId.of(uniqueId2)).collectList())
			.assertNext(events -> {
				assertThat(events).isNotEmpty();
				assertThat(events.getFirst()).isInstanceOf(InstanceRegisteredEvent.class);
			})
			.verifyComplete();
	}

	@Test
	void findEventsByInstanceId() {
		final var uniqueId = UUID.randomUUID().toString();
		final var instanceId = InstanceId.of(uniqueId);

		// Events for the same instance can be in one batch
		final var events = List.<InstanceEvent>of(
			createRegisteredEvent(uniqueId, "service-1"),
			createStatusChangedEvent(uniqueId, StatusInfo.ofUp()));

		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		StepVerifier.create(eventStore.find(instanceId).collectList())
			.assertNext(foundEvents -> {
				// At least our 2 events should be present (background activity may add more)
				assertThat(foundEvents).hasSizeGreaterThanOrEqualTo(2);
				assertThat(foundEvents.get(0)).isInstanceOf(InstanceRegisteredEvent.class);
			})
			.verifyComplete();
	}

	@Test
	void findReturnsEmptyForUnknownInstance() {
		final var unknownId = InstanceId.of("unknown");

		StepVerifier.create(eventStore.find(unknownId))
			.verifyComplete();
	}

	@Test
	void appendWithEmptyListDoesNothing() {
		// Appending an empty list should complete successfully without adding events
		StepVerifier.create(eventStore.append(List.of()))
			.verifyComplete();

		// Verify no events exist in the store
		StepVerifier.create(eventStore.findAll())
			.verifyComplete();
	}

	@Test
	void eventsAreSortedByTimestamp() {
		final var uniqueId = UUID.randomUUID().toString();
		final var events = List.<InstanceEvent>of(
			createRegisteredEvent(uniqueId, "service-1", 1L),
			createStatusChangedEvent(uniqueId, StatusInfo.ofUp(), 2L),
			createStatusChangedEvent(uniqueId, StatusInfo.ofDown(), 3L));

		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		StepVerifier.create(eventStore.find(InstanceId.of(uniqueId)).collectList())
			.assertNext(loadedEvents -> {
				assertThat(loadedEvents).hasSize(3);
				assertThat(loadedEvents.getFirst()).isInstanceOf(InstanceRegisteredEvent.class);
			})
			.verifyComplete();
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		return createRegisteredEvent(id, name, 1L);
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name, final long version) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, FAKE_URL).build();
		return new InstanceRegisteredEvent(instanceId, version, registration);
	}

	private InstanceStatusChangedEvent createStatusChangedEvent(final String id, final StatusInfo statusInfo) {
		return createStatusChangedEvent(id, statusInfo, 2L);
	}

	private InstanceStatusChangedEvent createStatusChangedEvent(final String id, final StatusInfo statusInfo, final long version) {
		final var instanceId = InstanceId.of(id);
		return new InstanceStatusChangedEvent(instanceId, version, statusInfo);
	}

	@Test
	void publishStoredEventsWithEvents() {
		final var uniqueId = UUID.randomUUID().toString();
		// Given: Store has events
		final var events = List.<InstanceEvent>of(createRegisteredEvent(uniqueId, "service-1"));
		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		// When/Then: publishStoredEvents does not throw
		eventStore.publishStoredEvents();

		// Verify events are still accessible (use find() to avoid interference from other tests)
		StepVerifier.create(eventStore.find(InstanceId.of(uniqueId)))
			.expectNextCount(1)
			.verifyComplete();
	}

	@Test
	void clearAllRemovesAllEvents() {
		final var uniqueId = UUID.randomUUID().toString();
		// Given: Store has events
		final var events = List.<InstanceEvent>of(createRegisteredEvent(uniqueId, "service-1"));
		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		// When
		eventStore.clearAll();

		// Then: Our specific instance's events should be gone
		StepVerifier.create(eventStore.find(InstanceId.of(uniqueId)))
			.verifyComplete();
	}

	/**
	 * Unit tests for the loadEventsFromDatabase() method using mocked EventPersistenceStore.
	 * These tests verify the startup loading and grouping logic without requiring a database.
	 */
	@Nested
	class LoadEventsFromDatabaseTest {

		@Test
		void loadsAndGroupsEventsByInstanceId() {
			final var uniqueId1 = UUID.randomUUID().toString();
			final var uniqueId2 = UUID.randomUUID().toString();
			final var uniqueId3 = UUID.randomUUID().toString();
			// Given: Mock persistence store with events for multiple instances
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			final var events = List.<InstanceEvent>of(
				createRegisteredEvent(uniqueId1, "service-1", 1L),
				createStatusChangedEvent(uniqueId1, StatusInfo.ofUp(), 2L),
				createRegisteredEvent(uniqueId2, "service-2", 1L),
				createStatusChangedEvent(uniqueId2, StatusInfo.ofDown(), 2L),
				createRegisteredEvent(uniqueId3, "service-3", 1L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			// When: Create JdbcEventStore (triggers loadEventsFromDatabase)
			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Then: Events are grouped by instance ID and accessible
			StepVerifier.create(store.findAll())
				.expectNextCount(5)
				.verifyComplete();

			StepVerifier.create(store.find(InstanceId.of(uniqueId1)))
				.expectNextCount(2)
				.verifyComplete();

			StepVerifier.create(store.find(InstanceId.of(uniqueId2)))
				.expectNextCount(2)
				.verifyComplete();

			StepVerifier.create(store.find(InstanceId.of(uniqueId3)))
				.expectNextCount(1)
				.verifyComplete();
		}

		@Test
		void handlesEmptyDatabaseGracefully() {
			// Given: Mock persistence store with no events
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			// When: Create JdbcEventStore
			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Then: Store is empty but functional
			StepVerifier.create(store.findAll())
				.verifyComplete();
		}

		@Test
		void persistEventsAsyncHandlesExceptionGracefully() {
			final var uniqueId = UUID.randomUUID().toString();
			// Given: Mock persistence store that throws on saveBatch
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			doThrow(new RuntimeException("DB connection failed")).when(mockPersistenceStore).saveBatch(anyList());
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// When: Append events (triggers async persist which will fail)
			final var events = List.<InstanceEvent>of(createRegisteredEvent(uniqueId, "service-1", 1L));
			StepVerifier.create(store.append(events))
				.verifyComplete();

			// Then: Events are still in memory despite persistence failure
			StepVerifier.create(store.findAll())
				.expectNextCount(1)
				.verifyComplete();

			// Verify saveBatch was called
			verify(mockPersistenceStore).saveBatch(anyList());
		}

		@Test
		void publishStoredEventsPublishesLoadedEvents() {
			final var uniqueId1 = UUID.randomUUID().toString();
			final var uniqueId2 = UUID.randomUUID().toString();
			// Given: Store loaded with events from database
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			final var events = List.<InstanceEvent>of(
				createRegisteredEvent(uniqueId1, "service-1", 1L),
				createRegisteredEvent(uniqueId2, "service-2", 1L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// When/Then: publishStoredEvents does not throw and events remain accessible
			store.publishStoredEvents();

			StepVerifier.create(store.findAll())
				.expectNextCount(2)
				.verifyComplete();
		}

		private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name, final long version) {
			final var instanceId = InstanceId.of(id);
			final var registration = Registration.create(name, FAKE_URL).build();
			return new InstanceRegisteredEvent(instanceId, version, registration);
		}

		private InstanceStatusChangedEvent createStatusChangedEvent(final String id, final StatusInfo statusInfo, final long version) {
			final var instanceId = InstanceId.of(id);
			return new InstanceStatusChangedEvent(instanceId, version, statusInfo);
		}
	}
}
