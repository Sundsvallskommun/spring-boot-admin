package se.sundsvall.springbootadmin.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EventJournalPropertiesTest {

	@Test
	void createWithValidValues() {
		final var properties = new EventJournalProperties(14, 500);

		assertThat(properties.retentionDays()).isEqualTo(14);
		assertThat(properties.maxEventsPerInstance()).isEqualTo(500);
	}

	@Test
	void createWithZeroRetentionDaysUsesDefault() {
		final var properties = new EventJournalProperties(0, 1000);

		assertThat(properties.retentionDays()).isEqualTo(30);
	}

	@Test
	void createWithNegativeRetentionDaysUsesDefault() {
		final var properties = new EventJournalProperties(-5, 1000);

		assertThat(properties.retentionDays()).isEqualTo(30);
	}

	@Test
	void createWithZeroMaxEventsPerInstanceUsesDefault() {
		final var properties = new EventJournalProperties(30, 0);

		assertThat(properties.maxEventsPerInstance()).isEqualTo(1000);
	}

	@Test
	void createWithNegativeMaxEventsPerInstanceUsesDefault() {
		final var properties = new EventJournalProperties(30, -100);

		assertThat(properties.maxEventsPerInstance()).isEqualTo(1000);
	}

	@Test
	void createWithAllDefaultValues() {
		final var properties = new EventJournalProperties(0, 0);

		assertThat(properties.retentionDays()).isEqualTo(30);
		assertThat(properties.maxEventsPerInstance()).isEqualTo(1000);
	}
}
