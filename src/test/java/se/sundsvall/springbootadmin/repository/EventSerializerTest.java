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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventSerializerTest {

	private EventSerializer serializer;

	@BeforeEach
	void setUp() {
		final var objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.registerModule(new AdminServerModule(new String[0]));
		serializer = new EventSerializer(objectMapper);
	}

	@Test
	void serializeInstanceRegisteredEvent() {
		final var event = createRegisteredEvent("test-id", "test-service");

		final var json = serializer.serialize(event);

		assertThat(json)
			.contains("\"instance\":\"test-id\"")
			.contains("\"registration\":")
			.contains("\"type\":\"REGISTERED\"");
	}

	@Test
	void deserializeInstanceRegisteredEvent() {
		final var original = createRegisteredEvent("test-id", "test-service");
		final var json = serializer.serialize(original);

		final var result = serializer.deserialize(json);

		assertThat(result).isNotNull();
		assertThat(result).isInstanceOf(InstanceRegisteredEvent.class);
		assertThat(result.getInstance()).isEqualTo(original.getInstance());
	}

	@ParameterizedTest
	@MethodSource("eventTypes")
	void deserializeEventTypes(final InstanceEvent event, final String description) {
		final var json = serializer.serialize(event);

		final var result = serializer.deserialize(json);

		assertThat(result).isNotNull();
		assertThat(result.getInstance()).isEqualTo(event.getInstance());
		assertThat(result.getClass()).isEqualTo(event.getClass());
	}

	static Stream<Arguments> eventTypes() {
		final var instanceId = InstanceId.of("test-id");
		final var registration = Registration.create("test-service", "http://localhost:8080").build();

		return Stream.of(
			Arguments.of(
				new InstanceRegisteredEvent(instanceId, 1L, registration),
				"InstanceRegisteredEvent"),
			Arguments.of(
				new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofUp()),
				"InstanceStatusChangedEvent with UP status"),
			Arguments.of(
				new InstanceStatusChangedEvent(instanceId, 1L, StatusInfo.ofDown()),
				"InstanceStatusChangedEvent with DOWN status"));
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
		final var event = createRegisteredEvent("test-id", "test-service");

		assertThatExceptionOfType(EventSerializationException.class)
			.isThrownBy(() -> brokenSerializer.serialize(event));
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, "http://localhost:8080").build();
		return new InstanceRegisteredEvent(instanceId, 1L, registration);
	}
}
