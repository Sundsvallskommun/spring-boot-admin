package se.sundsvall.springbootadmin.repository;

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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EventSerializerTest {

	private EventSerializer serializer;

	private static final String FAKE_URL = "http://localhost:8080";

	@BeforeEach
	void setUp() {
		final var mapper = JsonMapper.builder()
			.addModule(new AdminServerModule(new String[0]))
			.build();
		serializer = new EventSerializer(mapper);
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
	void serializeWhenObjectMapperThrowsJacksonException() {
		final var mockMapper = mock(ObjectMapper.class);
		when(mockMapper.writeValueAsString(any())).thenThrow(mock(JacksonException.class));
		final var brokenSerializer = new EventSerializer(mockMapper);
		final var event = createRegisteredEvent(UUID.randomUUID().toString(), "test-service");
		var instance = event.getInstance();
		System.out.println(instance);

		assertThatExceptionOfType(EventSerializationException.class)
			.isThrownBy(() -> brokenSerializer.serialize(event))
			.withMessageContaining("Failed to serialize event")
			.withCauseInstanceOf(JacksonException.class);
	}

	@Test
	void deserializeWithoutAdminServerModuleReturnsNull() {
		// Serialize with a proper mapper, then try to deserialize without AdminServerModule
		final var event = createRegisteredEvent(UUID.randomUUID().toString(), "test-service");
		final var json = serializer.serialize(event);

		final var brokenSerializer = new EventSerializer(JsonMapper.builder().build());

		assertThat(brokenSerializer.deserialize(json)).isNull();
	}

	private InstanceRegisteredEvent createRegisteredEvent(final String id, final String name) {
		final var instanceId = InstanceId.of(id);
		final var registration = Registration.create(name, FAKE_URL).build();
		return new InstanceRegisteredEvent(instanceId, 1L, registration);
	}
}
