package se.sundsvall.springbootadmin.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import se.sundsvall.springbootadmin.Application;

@SpringBootTest(classes = Application.class, webEnvironment = RANDOM_PORT)
@ActiveProfiles("junit")
class EventJournalPropertiesTest {

	@Autowired
	private EventJournalProperties properties;

	@Test
	void testProperties() {
		assertThat(properties.retentionDays()).isEqualTo(30);
		assertThat(properties.maxEventsPerInstance()).isEqualTo(1000);
		assertThat(properties.publishOnStartup()).isFalse();
	}
}
