package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.eventstore.InMemoryEventStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

	@Test
	void writesEmittedEventsToPersistenceStore() {
		final var eventStore = new InMemoryEventStore();
		final var persistenceStore = mock(EventPersistenceStore.class);
		final var captured = new AtomicReference<List<InstanceEvent>>();
		doAnswer(invocation -> {
			captured.set(invocation.getArgument(0));
			return null;
		}).when(persistenceStore).saveBatch(anyList());

		final var listener = new AuditEventListener(eventStore, persistenceStore);
		try {
			listener.start();

			final var event = registeredEvent();
			eventStore.append(List.of(event)).block();

			await().untilAsserted(() -> verify(persistenceStore, atLeastOnce()).saveBatch(anyList()));
		} finally {
			listener.stop();
		}
	}

	@Test
	void swallowsPersistenceErrorsToKeepSubscriptionAlive() {
		final var eventStore = new InMemoryEventStore();
		final var persistenceStore = mock(EventPersistenceStore.class);
		doThrow(new RuntimeException("transient db failure")).when(persistenceStore).saveBatch(anyList());

		final var listener = new AuditEventListener(eventStore, persistenceStore);
		try {
			listener.start();

			eventStore.append(List.of(registeredEvent())).block();
			eventStore.append(List.of(statusChangedEvent())).block();

			await().untilAsserted(() -> verify(persistenceStore, atLeastOnce()).saveBatch(anyList()));
		} finally {
			listener.stop();
		}
	}

	private static InstanceRegisteredEvent registeredEvent() {
		return new InstanceRegisteredEvent(
			InstanceId.of(UUID.randomUUID().toString()),
			1L,
			Registration.create("service-1", "http://localhost:8080").build());
	}

	private static InstanceStatusChangedEvent statusChangedEvent() {
		return new InstanceStatusChangedEvent(InstanceId.of(UUID.randomUUID().toString()), 1L, StatusInfo.ofUp());
	}
}
