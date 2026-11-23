package io.skipcheck.tm1.client.error;

import org.jspecify.annotations.Nullable;

import lombok.Getter;

/**
 * Something went wrong talking to TM1. The root of all the client's exceptions;
 * unchecked.
 *
 * <ul>
 * <li>{@link Tm1ApiException}: TM1 answered with an error.
 * <li>{@link Tm1TransportException}: no complete answer, e.g. connection refused or timed
 * out.
 * <li>{@link Tm1ProtocolException}: an answer the client couldn't read, including errors
 * that happened after TM1 had started a successful response.
 * </ul>
 */
public abstract sealed class Tm1Exception extends RuntimeException
		permits Tm1ApiException, Tm1TransportException, Tm1ProtocolException, Tm1UnsupportedOperationException {

	private static final long serialVersionUID = 1L;

	/**
	 * The request's HTTP method, or {@code null} if not known.
	 * @return the HTTP method, or {@code null}
	 */
	@Getter
	private final @Nullable String method;

	/**
	 * The request's URL, never including credentials, or {@code null} if not known.
	 * @return the URL, or {@code null}
	 */
	@Getter
	private final @Nullable String url;

	/**
	 * Creates an exception for a failed request.
	 * @param message the detail message
	 * @param method the request's HTTP method, or {@code null} if not known
	 * @param url the request's URL, without credentials, or {@code null} if not known
	 * @param cause the underlying failure, or {@code null}
	 */
	protected Tm1Exception(String message, @Nullable String method, @Nullable String url, @Nullable Throwable cause) {
		super(message, cause);
		this.method = method;
		this.url = url;
	}

	/**
	 * Whether sending the same request again could succeed, such as after a timeout or a
	 * 503.
	 * @return {@code true} if a retry could succeed
	 */
	public abstract boolean isRetryable();

	/**
	 * Whether the request may have taken effect on TM1 even though it failed. False only
	 * when TM1 certainly didn't act on it, such as a connection that was never made, or a
	 * request it rejected. Check this before retrying anything that isn't idempotent,
	 * like running a process.
	 * @return {@code false} only if TM1 certainly didn't act on the request
	 */
	public abstract boolean mayHaveApplied();

	static String describe(@Nullable String method, @Nullable String url) {
		return method == null ? "" : " (" + method + " " + url + ")";
	}

}
