package net.ajmyers.tm1.client.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * An OData error response body: {@code {"error":{"code":"278","message":"..."}}}.
 *
 * <p>
 * TM1 uses code {@code 278} for most errors (not found, bad request, precondition failed
 * and not implemented alike), so the code alone rarely identifies an error. See
 * {@link Tm1ErrorClassifier}.
 *
 * @param code the error code, as text
 * @param message the message
 * @param target what the error applies to, or {@code null}
 * @param details further errors, possibly empty
 * @param innerError service-specific detail, or {@code null}
 */
public record ODataError(String code, String message, @Nullable String target, List<ODataError> details,
		@Nullable JsonNode innerError) {

	/**
	 * Replaces a {@code null} code or message with an empty string and copies the
	 * details.
	 */
	public ODataError {
		code = code == null ? "" : code;
		message = message == null ? "" : message;
		details = List.copyOf(details);
	}

	/**
	 * Reads an error from a response body, if it is one: either {@code {"error":{...}}}
	 * or the inner object.
	 * @param body a response body, or {@code null}
	 * @return the error, or {@code null} if the body isn't an object with a {@code code}
	 * or {@code message}
	 */
	public static @Nullable ODataError from(@Nullable JsonNode body) {
		if (body == null || !body.isObject()) {
			return null;
		}
		JsonNode error = body.has("error") ? body.get("error") : body;
		if (!error.isObject() || !(error.has("code") || error.has("message"))) {
			return null;
		}
		List<ODataError> details = new ArrayList<>();
		JsonNode d = error.get("details");
		if (d != null && d.isArray()) {
			for (JsonNode item : d) {
				ODataError detail = from(item);
				if (detail != null) {
					details.add(detail);
				}
			}
		}
		return new ODataError(Objects.requireNonNullElse(text(error, "code"), ""),
				Objects.requireNonNullElse(text(error, "message"), ""), text(error, "target"), details,
				error.get("innererror"));
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value.asString();
	}
}
