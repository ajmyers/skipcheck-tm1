package io.skipcheck.tm1.client.odata;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads an {@code Edm.DateTimeOffset} that a server sometimes fills with something that
 * isn't one, such as TM1's {@code 0000-00-00T00:00Z}: as {@code null}, rather than
 * failing the whole response. The generated model uses it where a server is known to do
 * this.
 */
public final class LenientOffsetDateTimeDeserializer extends ValueDeserializer<@Nullable OffsetDateTime> {

	/** The deserializer; Jackson makes it from {@code @JsonDeserialize(using = …)}. */
	public LenientOffsetDateTimeDeserializer() {
	}

	@Override
	public @Nullable OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) {
		String text = parser.getValueAsString();
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(text);
		}
		catch (DateTimeParseException notADateTime) {
			return null;
		}
	}

}
