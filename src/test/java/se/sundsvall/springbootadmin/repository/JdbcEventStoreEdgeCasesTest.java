package se.sundsvall.springbootadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import reactor.test.StepVerifier;

/**
 * These tests focus on behavior that's difficult to test with a real database,
 * particularly the version conflict handling during rolling restarts.
 */
class JdbcEventStoreEdgeCasesTest {

	@Mock
	private EventPersistenceStore mockPersistenceStore;

	@InjectMocks
	private JdbcEventStore jdbcEventStore;

	@BeforeEach
	void setUp() {
		mockPersistenceStore = mock(EventPersistenceStore.class);
	}

	@Nested
	class VersionConflictHandling {

		@Test
		void handlesVersionConflictByRefreshingFromDatabase() {
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Add initial event with version 1
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			final var event1 = createRegisteredEvent(instanceId, 1L);
			StepVerifier.create(store.append(List.of(event1)))
				.verifyComplete();

			// Wait for async persistence to complete before setting up new mocks
			await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
				verify(mockPersistenceStore).saveBatch(List.of(event1));
			});

			// Another pod already persisted version 2
			final var event2FromDb = createStatusChangedEvent(instanceId, StatusInfo.ofUp(), 2L);
			when(mockPersistenceStore.loadByInstanceId(instanceId))
				.thenReturn(List.of(event1, event2FromDb));

			// Try to append event with version 1 again (simulates duplicate event from another pod)
			final var duplicateEvent = createRegisteredEvent(instanceId, 1L);
			StepVerifier.create(store.append(List.of(duplicateEvent)))
				.verifyComplete();

			// loadByInstanceId should have been called to refresh cache
			verify(mockPersistenceStore).loadByInstanceId(instanceId);
		}

		@Test
		void conflictWithEmptyDatabaseRefreshStillWorks() {
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Add initial event
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			final var event1 = createRegisteredEvent(instanceId, 1L);
			StepVerifier.create(store.append(List.of(event1)))
				.verifyComplete();

			// Wait for async persistence to complete before setting up new mocks
			await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
				verify(mockPersistenceStore).saveBatch(List.of(event1));
			});

			// Database returns empty (maybe events were cleaned up)
			when(mockPersistenceStore.loadByInstanceId(instanceId))
				.thenReturn(List.of());

			// Try to append conflicting event
			final var conflictingEvent = createRegisteredEvent(instanceId, 1L);
			StepVerifier.create(store.append(List.of(conflictingEvent)))
				.verifyComplete();

			// Should have attempted refresh
			verify(mockPersistenceStore).loadByInstanceId(instanceId);
		}

		@Test
		void successfulAppendDoesNotTriggerConflictHandling() {
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Append events with correct sequential versions
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			final var events = List.of(
				createRegisteredEvent(instanceId, 1L),
				createStatusChangedEvent(instanceId, StatusInfo.ofUp(), 2L));

			StepVerifier.create(store.append(events))
				.verifyComplete();

			// loadByInstanceId should NOT have been called (no conflict)
			verify(mockPersistenceStore, never()).loadByInstanceId(any());
		}

		@Test
		void conflictResolutionSucceedsWhenRetryWorks() {
			// Store starts with two events (version 1 and 2)
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			final var existingEvent1 = createRegisteredEvent(instanceId, 1L);
			final var existingEvent2 = createStatusChangedEvent(instanceId, StatusInfo.ofUp(), 2L);
			when(mockPersistenceStore.loadAll()).thenReturn(List.of(existingEvent1, existingEvent2));

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Database has a newer event (version 3) that we don't have in cache
			final var newerEvent = createStatusChangedEvent(instanceId, StatusInfo.ofDown(), 3L);
			when(mockPersistenceStore.loadByInstanceId(instanceId))
				.thenReturn(List.of(existingEvent1, existingEvent2, newerEvent));

			// Try to append version 2 again (which conflicts with in-memory version 2)
			// After refresh from DB (which has v1, v2, v3), the retry will still fail because v2 is still a duplicate
			final var conflictingEvent = createStatusChangedEvent(instanceId, StatusInfo.ofDown(), 2L);
			StepVerifier.create(store.append(List.of(conflictingEvent)))
				.verifyComplete();

			// Verify the store was refreshed
			verify(mockPersistenceStore).loadByInstanceId(instanceId);
		}
	}

	@Nested
	class EdgeCases {

		@Test
		void appendWithEmptyListReturnsImmediately() {
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			StepVerifier.create(store.append(List.of()))
				.verifyComplete();

			// No persistence operations should have been called
			verify(mockPersistenceStore, never()).saveBatch(any());
		}

		@Test
		void publishStoredEventsWithEmptyCache() {
			when(mockPersistenceStore.loadAll()).thenReturn(List.of());

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Should not throw
			store.publishStoredEvents();

			StepVerifier.create(store.findAll())
				.verifyComplete();
		}

		@Test
		void clearAllRemovesAllEventsFromCache() {
			// Store starts with events
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			final var events = List.of(
				createRegisteredEvent(instanceId, 1L),
				createStatusChangedEvent(instanceId, StatusInfo.ofUp(), 2L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			// Verify events exist
			StepVerifier.create(store.findAll())
				.expectNextCount(2)
				.verifyComplete();

			store.clearAll();
			// Verify cache is empty
			StepVerifier.create(store.findAll())
				.verifyComplete();
		}
	}

	@Nested
	class StartupLoading {

		@Test
		void loadsEventsFromDatabaseOnStartup() {
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			final var events = List.of(
				createRegisteredEvent(instanceId, 1L),
				createStatusChangedEvent(instanceId, StatusInfo.ofUp(), 2L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			StepVerifier.create(store.findAll())
				.expectNextCount(2)
				.verifyComplete();

			verify(mockPersistenceStore).loadAll();
		}

		@Test
		void sortsEventsByVersionOnLoad() {
			final var instanceId = InstanceId.of(UUID.randomUUID().toString());
			// Return events in wrong order
			final var events = List.of(
				createStatusChangedEvent(instanceId, StatusInfo.ofUp(), 3L),
				createRegisteredEvent(instanceId, 1L),
				createStatusChangedEvent(instanceId, StatusInfo.ofDown(), 2L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			StepVerifier.create(store.find(instanceId).collectList())
				.assertNext(loadedEvents -> {
					assertThat(loadedEvents).hasSize(3);
					assertThat(loadedEvents.get(0).getVersion()).isEqualTo(1L);
					assertThat(loadedEvents.get(1).getVersion()).isEqualTo(2L);
					assertThat(loadedEvents.get(2).getVersion()).isEqualTo(3L);
				})
				.verifyComplete();
		}

		@Test
		void groupsEventsByInstanceId() {
			final var instance1 = InstanceId.of(UUID.randomUUID().toString());
			final var instance2 = InstanceId.of(UUID.randomUUID().toString());
			final var events = List.of(
				createRegisteredEvent(instance1, 1L),
				createRegisteredEvent(instance2, 1L),
				createStatusChangedEvent(instance1, StatusInfo.ofUp(), 2L));
			when(mockPersistenceStore.loadAll()).thenReturn(events);

			final var store = new JdbcEventStore(100, mockPersistenceStore);

			StepVerifier.create(store.find(instance1))
				.expectNextCount(2)
				.verifyComplete();

			StepVerifier.create(store.find(instance2))
				.expectNextCount(1)
				.verifyComplete();
		}
	}

	private InstanceRegisteredEvent createRegisteredEvent(final InstanceId instanceId, final long version) {
		final var registration = Registration.create("test-service", "http://test:8080").build();
		return new InstanceRegisteredEvent(instanceId, version, registration);
	}

	private InstanceStatusChangedEvent createStatusChangedEvent(final InstanceId instanceId, final StatusInfo status, final long version) {
		return new InstanceStatusChangedEvent(instanceId, version, status);
	}
}
