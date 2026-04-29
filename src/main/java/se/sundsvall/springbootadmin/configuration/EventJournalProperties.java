package se.sundsvall.springbootadmin.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "spring.boot.admin.journal")
public record EventJournalProperties(
	@DefaultValue("30") int retentionDays,
	@DefaultValue("1000") int maxEventsPerInstance) {
}
