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

	@BeforeEach
	void setUp() {
		// Clear in-memory cache before each test
		eventStore.clearAll();
	}

	@Test
	void appendAndFindAllEvents() {
		// Events must be grouped by instance for append (ConcurrentMapEventStore requirement)
		final var events1 = List.<InstanceEvent>of(createRegisteredEvent("id-1", "service-1"));
		final var events2 = List.<InstanceEvent>of(createRegisteredEvent("id-2", "service-2"));

		StepVerifier.create(eventStore.append(events1).then(eventStore.append(events2)))
			.verifyComplete();

		StepVerifier.create(eventStore.findAll())
			.expectNextCount(2)
			.verifyComplete();
	}

	@Test
	void findEventsByInstanceId() {
		final var instanceId = InstanceId.of("id-1");
		// Events for the same instance can be in one batch
		final var events1 = List.<InstanceEvent>of(
			createRegisteredEvent("id-1", "service-1"),
			createStatusChangedEvent("id-1", StatusInfo.ofUp()));
		final var events2 = List.<InstanceEvent>of(createRegisteredEvent("id-2", "service-2"));

		StepVerifier.create(eventStore.append(events1).then(eventStore.append(events2)))
			.verifyComplete();

		StepVerifier.create(eventStore.find(instanceId))
			.expectNextCount(2)
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
		StepVerifier.create(eventStore.append(List.of()))
			.verifyComplete();

		StepVerifier.create(eventStore.findAll())
			.verifyComplete();
	}

	@Test
	void eventsAreSortedByTimestamp() {
		// Each event must have a unique, incrementing version
		final var events = List.<InstanceEvent>of(
			createRegisteredEvent("id-1", "service-1", 1L),
			createStatusChangedEvent("id-1", StatusInfo.ofUp(), 2L),
			createStatusChangedEvent("id-1", StatusInfo.ofDown(), 3L));

		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		StepVerifier.create(eventStore.findAll().collectList())
			.assertNext(loadedEvents -> {
				assertThat(loadedEvents).hasSize(3);
				assertThat(loadedEvents.get(0)).isInstanceOf(InstanceRegisteredEvent.class);
			})
			.verifyComplete();
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		return createRegisteredEvent(id, name, 1L);
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name, final long version) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, "http://localhost:8080").build();
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
		// Given: Store has events
		final var events = List.<InstanceEvent>of(createRegisteredEvent("id-1", "service-1"));
		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		// When/Then: publishStoredEvents does not throw
		eventStore.publishStoredEvents();

		// Verify events are still accessible
		StepVerifier.create(eventStore.findAll())
			.expectNextCount(1)
			.verifyComplete();
	}

	@Test
	void publishStoredEventsWithEmptyStore() {
		// When/Then: publishStoredEvents on empty store does not throw
		eventStore.publishStoredEvents();

		StepVerifier.create(eventStore.findAll())
			.verifyComplete();
	}

	@Test
	void clearAllRemovesAllEvents() {
		// Given: Store has events
		final var events = List.<InstanceEvent>of(createRegisteredEvent("id-1", "service-1"));
		StepVerifier.create(eventStore.append(events))
			.verifyComplete();

		// When
		eventStore.clearAll();

		// Then
		StepVerifier.create(eventStore.findAll())
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
			// Given: Mock persistence store with events for multiple instances
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			final var events = List.<InstanceEvent>of(
				createRegisteredEvent("id-1", "service-1", 1L),
				createStatusChangedEvent("id-1", StatusInfo.ofUp(), 2L),
				createRegisteredEvent("id-2", "service-2", 1L),
				createStatusChangedEvent("id-2", StatusInfo.ofDown(), 2L),
				createRegisteredEvent("id-3", "service-3", 1L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			// When: Create JdbcEventStore (triggers loadEventsFromDatabase)
			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Then: Events are grouped by instance ID and accessible
			StepVerifier.create(store.findAll())
				.expectNextCount(5)
				.verifyComplete();

			StepVerifier.create(store.find(InstanceId.of("id-1")))
				.expectNextCount(2)
				.verifyComplete();

			StepVerifier.create(store.find(InstanceId.of("id-2")))
				.expectNextCount(2)
				.verifyComplete();

			StepVerifier.create(store.find(InstanceId.of("id-3")))
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
			// Given: Mock persistence store that throws on saveBatch
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			doThrow(new RuntimeException("DB connection failed")).when(mockPersistenceStore).saveBatch(anyList());
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// When: Append events (triggers async persist which will fail)
			final var events = List.<InstanceEvent>of(createRegisteredEvent("id-1", "service-1", 1L));
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
			// Given: Store loaded with events from database
			final var mockPersistenceStore = mock(EventPersistenceStore.class);
			final var events = List.<InstanceEvent>of(
				createRegisteredEvent("id-1", "service-1", 1L),
				createRegisteredEvent("id-2", "service-2", 1L));
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
			final var registration = Registration.create(name, "http://localhost:8080").build();
			return new InstanceRegisteredEvent(instanceId, version, registration);
		}

		private InstanceStatusChangedEvent createStatusChangedEvent(final String id, final StatusInfo statusInfo, final long version) {
			final var instanceId = InstanceId.of(id);
			return new InstanceStatusChangedEvent(instanceId, version, statusInfo);
		}
	}
}
