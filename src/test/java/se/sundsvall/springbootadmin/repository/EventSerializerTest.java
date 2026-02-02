package se.sundsvall.springbootadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceRegisteredEvent;
import de.codecentric.boot.admin.server.domain.events.InstanceStatusChangedEvent;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import de.codecentric.boot.admin.server.utils.jackson.AdminServerModule;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventSerializerTest {

	private EventSerializer serializer;

	private static final String FAKE_URL = "http://cannot.reach.this.url:8080";

	@BeforeEach
	void setUp() {
		final var objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.registerModule(new AdminServerModule(new String[0]));
		serializer = new EventSerializer(objectMapper);
	}

	@Test
	void serializeInstanceRegisteredEvent() {
		final var uniqueId = UUID.randomUUID().toString();
		final var event = createRegisteredEvent(uniqueId, "test-service");

		final var json = serializer.serialize(event);

		assertThat(json)
			.contains("\"instance\":\"" + uniqueId + "\"")
			.contains("\"registration\":")
			.contains("\"type\":\"REGISTERED\"");
	}

	@Test
	void deserializeInstanceRegisteredEvent() {
		final var uniqueId = UUID.randomUUID().toString();
		final var original = createRegisteredEvent(uniqueId, "test-service");
		final var json = serializer.serialize(original);

		final var result = serializer.deserialize(json);

		assertThat(result).isNotNull()
			.isInstanceOf(InstanceRegisteredEvent.class);
		assertThat(result.getInstance()).isEqualTo(original.getInstance());
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("eventTypes")
	void deserializeEventTypes(final String testName, final InstanceEvent event) {
		final var json = serializer.serialize(event);

		final var result = serializer.deserialize(json);

		assertThat(result).isNotNull();
		assertThat(result.getInstance()).isEqualTo(event.getInstance());
		assertThat(result.getClass()).isEqualTo(event.getClass());
	}

	static Stream<Arguments> eventTypes() {
		final var instanceId = InstanceId.of(UUID.randomUUID().toString());
		final var registration = Registration.create("test-service", FAKE_URL).build();

		return Stream.of(
			Arguments.of(
				"InstanceRegisteredEvent",
				new InstanceRegisteredEvent(instanceId, 1L, registration)),
			Arguments.of(
				"InstanceStatusChangedEvent with UP status",
				new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofUp())),
			Arguments.of(
				"InstanceStatusChangedEvent with DOWN status",
				new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown())));
	}

	@Test
	void deserializeMalformedJsonReturnsNull() {
		final var result = serializer.deserialize("not valid json");

		assertThat(result).isNull();
	}

	@Test
	void deserializeEmptyJsonReturnsNull() {
		final var result = serializer.deserialize("{}");

		assertThat(result).isNull();
	}

	@Test
	void serializeWithBrokenObjectMapperThrowsException() {
		// Create a serializer with a broken ObjectMapper (no AdminServerModule)
		final var brokenSerializer = new EventSerializer(new ObjectMapper());
		final var event = createRegisteredEvent(UUID.randomUUID().toString(), "test-service");

		assertThatExceptionOfType(EventSerializationException.class)
			.isThrownBy(() -> brokenSerializer.serialize(event));
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, FAKE_URL).build();
		return new InstanceRegisteredEvent(instanceId, 1L, registration);
	}
}
