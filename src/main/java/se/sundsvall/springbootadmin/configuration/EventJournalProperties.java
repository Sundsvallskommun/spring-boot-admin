package se.sundsvall.springbootadmin.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.boot.admin.journal")
public record EventJournalProperties(
	int retentionDays,
	int maxEventsPerInstance) {

	public EventJournalProperties {
		if (retentionDays <= 0) {
			retentionDays = 30;
		}
		if (maxEventsPerInstance <= 0) {
			maxEventsPerInstance = 1000;
		}
	}
}
