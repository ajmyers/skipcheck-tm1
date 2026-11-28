// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.error;

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

	/** The longest plain-text message kept, so an HTML error page can't fill a log. */
	private static final int MAX_TEXT = 1000;

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
	 * Reads an error from a response body, if it is one: an OData error object
	 * ({@code {"error":{...}}} or the inner object), or a TM1 12 auth/gateway error
	 * ({@code {"error":"invalid_token","error_details":"..."}} or OAuth 2.0
	 * {@code error_description}).
	 * @param body a response body, or {@code null}
	 * @return the error, or {@code null} if the body isn't a recognized error object
	 */
	public static @Nullable ODataError from(@Nullable JsonNode body) {
		if (body == null || !body.isObject()) {
			return null;
		}
		JsonNode error = body.has("error") ? body.get("error") : body;
		if (error.isString()) {
			String code = error.asString();
			String message = text(body, "error_details");
			if (message == null || message.isEmpty()) {
				message = text(body, "error_description");
			}
			if (code.isEmpty() && (message == null || message.isEmpty())) {
				return null;
			}
			return new ODataError(code, Objects.requireNonNullElse(message, ""), null, List.of(), null);
		}
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

	/**
	 * Reads an error from a plain-text body, as TM1 sends for some failures
	 * (impersonation, for one): a status line such as {@code Error: Bad Request}, then
	 * the message. The status line is dropped, and the remaining lines become the
	 * message, with no code.
	 * @param body the response body as text
	 * @return the error, or {@code null} if the body has no message
	 */
	public static @Nullable ODataError fromText(String body) {
		StringBuilder message = new StringBuilder();
		for (String line : body.split("\\R", -1)) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("Error:")) {
				continue;
			}
			message.append(message.length() == 0 ? "" : " ").append(trimmed);
		}
		if (message.length() == 0) {
			return null;
		}
		String text = message.length() > MAX_TEXT ? message.substring(0, MAX_TEXT) + "…" : message.toString();
		return new ODataError("", text, null, List.of(), null);
	}

	private static @Nullable String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		if (value == null || value.isNull()) {
			return null;
		}
		return value.isValueNode() ? value.asString() : value.toString();
	}
}
