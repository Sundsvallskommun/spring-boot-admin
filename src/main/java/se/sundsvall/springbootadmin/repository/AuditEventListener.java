package se.sundsvall.springbootadmin.repository;

import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.eventstore.InstanceEventStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Subscribes to the SBA event stream and writes every emitted event to the audit table in MariaDB.
 * <p>
 * The audit log is write-only — it is never read back to drive runtime behaviour. Live state lives in the
 * Hazelcast-backed event store. This listener exists purely so that historical questions ("how often did service X
 * flap last week?") can be answered by querying the database directly.
 * <p>
 * Both SBA replicas run this listener and observe the same Hazelcast event stream, so each event is written twice.
 * The unique constraint {@code uk_instance_version} on (instance_id, version) deduplicates at the DB layer; the
 * resulting {@link org.springframework.dao.DuplicateKeyException} is swallowed inside
 * {@link EventPersistenceStore#saveBatch}.
 */
@Component
public class AuditEventListener {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuditEventListener.class);

	private final InstanceEventStore eventStore;
	private final EventPersistenceStore persistenceStore;
	private Disposable subscription;

	public AuditEventListener(final InstanceEventStore eventStore, final EventPersistenceStore persistenceStore) {
		this.eventStore = eventStore;
		this.persistenceStore = persistenceStore;
	}

	@PostConstruct
	public void start() {
		subscription = Flux.from(eventStore)
			.publishOn(Schedulers.boundedElastic())
			.subscribe(this::persist, error -> LOGGER.error("Audit event subscription error", error));
		LOGGER.info("Audit event listener started");
	}

	@PreDestroy
	public void stop() {
		if (subscription != null && !subscription.isDisposed()) {
			subscription.dispose();
			LOGGER.info("Audit event listener stopped");
		}
	}

	private void persist(final InstanceEvent event) {
		try {
			persistenceStore.saveBatch(List.of(event));
		} catch (final Exception e) {
			LOGGER.error("Failed to write audit event for instance {}: {}", event.getInstance(), e.getMessage());
		}
	}
}
