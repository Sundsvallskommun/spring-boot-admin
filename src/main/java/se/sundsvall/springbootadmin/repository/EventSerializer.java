package se.sundsvall.springbootadmin.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.codecentric.boot.admin.server.domain.events.InstanceEvent;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles JSON serialization and deserialization of InstanceEvent objects.
 */
public class EventSerializer {

	private static final Logger LOGGER = LoggerFactory.getLogger(EventSerializer.class);

	private final ObjectMapper objectMapper;

	public EventSerializer(final ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Serialize an InstanceEvent to JSON string.
	 *
	 * @param  event                       the event to serialize
	 * @return                             JSON string representation
	 * @throws EventSerializationException if serialization fails
	 */
	@NonNull
	public String serialize(@NonNull final InstanceEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (final JsonProcessingException e) {
			throw new EventSerializationException("Failed to serialize event: " + event.getInstance(), e);
		}
	}

	/**
	 * Deserialize JSON string to InstanceEvent.
	 *
	 * @param  json the JSON string
	 * @return      the deserialized InstanceEvent, or null if deserialization fails
	 */
	@Nullable
	public InstanceEvent deserialize(@NonNull final String json) {
		try {
			return objectMapper.readValue(json, InstanceEvent.class);
		} catch (final JsonProcessingException e) {
			LOGGER.error("Failed to deserialize event: {}", e.getMessage());
			return null;
		}
	}
}
