package se.sundsvall.springbootadmin.configuration;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import de.codecentric.boot.admin.server.domain.entities.EventsourcingInstanceRepository;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.eventstore.InstanceEventStore;
import de.codecentric.boot.admin.server.services.StatusUpdateTrigger;
import de.codecentric.boot.admin.server.services.StatusUpdater;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import se.sundsvall.springbootadmin.repository.EventPersistenceStore;
import se.sundsvall.springbootadmin.repository.JdbcEventStore;

@Configuration
@EnableConfigurationProperties(EventJournalProperties.class)
public class PersistenceConfig {

	@Bean
	@Primary
	public JdbcEventStore instanceEventStore(
		final EventPersistenceStore eventPersistenceStore,
		final EventJournalProperties properties) {
		return new JdbcEventStore(properties.maxEventsPerInstance(), eventPersistenceStore);
	}

	@Bean
	@Primary
	public InstanceRepository instanceRepository(final InstanceEventStore eventStore) {
		return new EventsourcingInstanceRepository(eventStore);
	}

	/**
	 * After the application is fully started and all subscribers (like StatusUpdateTrigger) are wired,
	 * publish stored events so that status checks are triggered for all known instances.
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void onApplicationReady(final ApplicationReadyEvent event) {
		event.getApplicationContext().getBean(JdbcEventStore.class).publishStoredEvents();
	}

	/**
	 * StatusUpdateTrigger that subscribes to the event store's event stream.
	 */
	@Bean(initMethod = "start", destroyMethod = "stop")
	@Primary
	public StatusUpdateTrigger statusUpdateTrigger(
		final StatusUpdater statusUpdater,
		final InstanceEventStore eventStore,
		final AdminServerProperties adminServerProperties) {
		final var monitor = adminServerProperties.getMonitor();
		return new StatusUpdateTrigger(statusUpdater, eventStore,
			monitor.getStatusInterval(), monitor.getStatusLifetime(), monitor.getStatusLifetime());
	}
}
