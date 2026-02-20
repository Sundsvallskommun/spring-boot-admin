package se.sundsvall.springbootadmin.repository;

/**
 * Exception thrown when event serialization fails.
 */
public class EventSerializationException extends RuntimeException {

	public EventSerializationException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
