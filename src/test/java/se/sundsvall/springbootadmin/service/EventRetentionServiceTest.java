package se.sundsvall.springbootadmin.service;

import de.codecentric.boot.admin.server.domain.values.InstanceId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import se.sundsvall.springbootadmin.configuration.EventJournalProperties;
import se.sundsvall.springbootadmin.repository.EventPersistenceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventRetentionServiceTest {

	@Mock
	private EventPersistenceStore persistenceStore;

	@Captor
	private ArgumentCaptor<Instant> cutoffCaptor;

	private EventRetentionService retentionService;

	@BeforeEach
	void setUp() {
		final var properties = new EventJournalProperties(30, 1000, true);
		retentionService = new EventRetentionService(persistenceStore, properties);
	}

	@Test
	void cleanupDeletesOldEvents() {
		when(persistenceStore.deleteOlderThan(any())).thenReturn(10);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of());

		retentionService.cleanup();

		verify(persistenceStore).deleteOlderThan(cutoffCaptor.capture());
		final var cutoff = cutoffCaptor.getValue();
		assertThat(cutoff).isBefore(Instant.now());
	}

	@Test
	void cleanupDeletesExcessEventsPerInstance() {
		final var instanceId1 = InstanceId.of(UUID.randomUUID().toString());
		final var instanceId2 = InstanceId.of(UUID.randomUUID().toString());
		final var instanceId3 = InstanceId.of(UUID.randomUUID().toString());

		final var instanceIds = List.of(
			instanceId1, instanceId2, instanceId3);

		when(persistenceStore.deleteOlderThan(any())).thenReturn(0);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(instanceIds);
		when(persistenceStore.deleteExcessEventsForInstance(any(), anyInt())).thenReturn(5);

		retentionService.cleanup();

		verify(persistenceStore).deleteExcessEventsForInstance(instanceId1, 1000);
		verify(persistenceStore).deleteExcessEventsForInstance(instanceId2, 1000);
		verify(persistenceStore).deleteExcessEventsForInstance(instanceId3, 1000);
	}

	@Test
	void cleanupHandlesEmptyInstanceList() {
		when(persistenceStore.deleteOlderThan(any())).thenReturn(0);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of());

		retentionService.cleanup();

		verify(persistenceStore).deleteOlderThan(any());
		verify(persistenceStore).getDistinctInstanceIds();
	}

	@Test
	void cleanupWithCustomRetentionDays() {
		// Use custom properties with 7 days retention
		final var properties = new EventJournalProperties(7, 500, true);
		final var service = new EventRetentionService(persistenceStore, properties);

		when(persistenceStore.deleteOlderThan(any())).thenReturn(0);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of(InstanceId.of("id-1")));
		when(persistenceStore.deleteExcessEventsForInstance(any(), anyInt())).thenReturn(0);

		service.cleanup();

		verify(persistenceStore).deleteExcessEventsForInstance(any(), eq(500));
	}
}
