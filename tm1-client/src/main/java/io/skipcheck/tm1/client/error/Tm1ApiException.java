package io.skipcheck.tm1.client.error;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import lombok.Getter;

/**
 * TM1 answered with an error status. Carries the status, the parsed OData error and what
 * it means ({@link #kind()}). The subclass follows from the kind first and the status
 * second, so a "not found" TM1 reports as a 400 is still a {@link Tm1NotFoundException}.
 */
public sealed class Tm1ApiException extends Tm1Exception
		permits Tm1BadRequestException, Tm1AuthenticationException, Tm1ForbiddenException, Tm1NotFoundException,
		Tm1ConflictException, Tm1PreconditionFailedException, Tm1NotImplementedException, Tm1ServerException {

	private static final long serialVersionUID = 1L;

	/**
	 * The HTTP status.
	 * @return the status code, such as {@code 404}
	 */
	@Getter
	private final int status;

	/**
	 * What the error means.
	 * @return the kind, never {@code null}
	 */
	@Getter
	private final Tm1ErrorKind kind;

	/**
	 * The OData error body, or {@code null} if the response didn't have one.
	 * @return the error, or {@code null}
	 */
	@Getter
	private final transient @Nullable ODataError error;

	/**
	 * Creates an exception for an error response.
	 * @param status the HTTP status
	 * @param kind what the error means
	 * @param error the parsed error body, or {@code null}
	 * @param method the request's HTTP method
	 * @param url the request's URL, without credentials
	 */
	protected Tm1ApiException(int status, Tm1ErrorKind kind, @Nullable ODataError error, String method, String url) {
		super(message(status, error, method, url), method, url, null);
		this.status = status;
		this.kind = Objects.requireNonNull(kind, "kind");
		this.error = error;
	}

	/**
	 * The exception for an error response, classified with {@code classifier}.
	 * @param status the HTTP status
	 * @param error the parsed error body, or {@code null} if the body wasn't an OData
	 * error
	 * @param method the request's HTTP method
	 * @param url the request's URL, without credentials
	 * @param classifier works out the error's kind from the status and body
	 * @return the subclass matching the kind and status
	 */
	public static Tm1ApiException of(int status, @Nullable ODataError error, String method, String url,
			Tm1ErrorClassifier classifier) {
		Tm1ErrorKind kind = classifier.classify(status, error);
		return of(status, kind, error, method, url);
	}

	/**
	 * The exception for an error response whose kind is already known.
	 * @param status the HTTP status
	 * @param kind what the error means
	 * @param error the parsed error body, or {@code null} if the body wasn't an OData
	 * error
	 * @param method the request's HTTP method
	 * @param url the request's URL, without credentials
	 * @return the subclass matching the kind first and the status second
	 */
	public static Tm1ApiException of(int status, Tm1ErrorKind kind, @Nullable ODataError error, String method,
			String url) {
		switch (kind) {
			case NOT_FOUND -> {
				return new Tm1NotFoundException(status, kind, error, method, url);
			}
			case ALREADY_EXISTS, LOCKED, IN_USE, CONFLICT -> {
				return new Tm1ConflictException(status, kind, error, method, url);
			}
			case PRECONDITION_FAILED -> {
				return new Tm1PreconditionFailedException(status, kind, error, method, url);
			}
			case UNAUTHENTICATED, CREDENTIAL_REJECTED -> {
				return new Tm1AuthenticationException(status, kind, error, method, url);
			}
			case FORBIDDEN, NO_READ_RIGHTS, IMPERSONATION_REFUSED -> {
				return new Tm1ForbiddenException(status, kind, error, method, url);
			}
			case NOT_IMPLEMENTED -> {
				return new Tm1NotImplementedException(status, kind, error, method, url);
			}
			default -> {
				if (status >= 500) {
					return new Tm1ServerException(status, kind, error, method, url);
				}
				if (status >= 400) {
					return new Tm1BadRequestException(status, kind, error, method, url);
				}
				return new Tm1ApiException(status, kind, error, method, url);
			}
		}
	}

	/** True for statuses where trying again later may work: 429, 502, 503 and 504. */
	@Override
	public boolean isRetryable() {
		return status == 429 || status == 502 || status == 503 || status == 504;
	}

	/**
	 * False for client errors (4xx), which TM1 rejected; true for server errors, except
	 * 501.
	 */
	@Override
	public boolean mayHaveApplied() {
		return status >= 500 && status != 501;
	}

	private static String message(int status, @Nullable ODataError error, String method, String url) {
		String detail = error == null || error.message().isEmpty() ? "" : ": " + error.message();
		String code = error == null || error.code().isEmpty() ? "" : " [code " + error.code() + "]";
		return "TM1 returned " + status + detail + code + describe(method, url);
	}

}
