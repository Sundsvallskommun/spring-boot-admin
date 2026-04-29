package se.sundsvall.springbootadmin.service;

import de.codecentric.boot.admin.server.domain.values.InstanceId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import se.sundsvall.springbootadmin.configuration.EventJournalProperties;
import se.sundsvall.springbootadmin.repository.EventPersistenceStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventRetentionServiceTest {

	@Mock
	private EventPersistenceStore persistenceStore;

	@Spy
	private EventJournalProperties properties = new EventJournalProperties(30, 1000);

	@InjectMocks
	private EventRetentionService retentionService;

	@Captor
	private ArgumentCaptor<Instant> cutoffCaptor;

	@Test
	void cleanupDeletesOldEventsUsingRetentionWindow() {
		when(persistenceStore.deleteOlderThan(any())).thenReturn(10);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of());

		retentionService.cleanup();

		verify(persistenceStore).deleteOlderThan(cutoffCaptor.capture());
		final var cutoff = cutoffCaptor.getValue();
		assertThat(cutoff)
			.isBefore(Instant.now())
			.isAfter(Instant.now().minus(31, ChronoUnit.DAYS));
	}

	@Test
	void cleanupDeletesExcessEventsPerInstance() {
		final var id1 = InstanceId.of(UUID.randomUUID().toString());
		final var id2 = InstanceId.of(UUID.randomUUID().toString());
		when(persistenceStore.deleteOlderThan(any())).thenReturn(0);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of(id1, id2));
		when(persistenceStore.deleteExcessEventsForInstance(any(), anyInt())).thenReturn(5);

		retentionService.cleanup();

		verify(persistenceStore).deleteExcessEventsForInstance(id1, 1000);
		verify(persistenceStore).deleteExcessEventsForInstance(id2, 1000);
	}

	@Test
	void cleanupHandlesEmptyInstanceList() {
		when(persistenceStore.deleteOlderThan(any())).thenReturn(0);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of());

		retentionService.cleanup();

		verify(persistenceStore).deleteOlderThan(any());
		verify(persistenceStore).getDistinctInstanceIds();
		verifyNoMoreInteractions(persistenceStore);
	}

	@Test
	void cleanupUsesCustomMaxEventsPerInstance() {
		final var customProps = new EventJournalProperties(7, 500);
		final var service = new EventRetentionService(persistenceStore, customProps);
		when(persistenceStore.deleteOlderThan(any())).thenReturn(0);
		when(persistenceStore.getDistinctInstanceIds()).thenReturn(List.of(InstanceId.of("id-1")));
		when(persistenceStore.deleteExcessEventsForInstance(any(), anyInt())).thenReturn(0);

		service.cleanup();

		verify(persistenceStore).deleteExcessEventsForInstance(any(), eq(500));
	}
}
