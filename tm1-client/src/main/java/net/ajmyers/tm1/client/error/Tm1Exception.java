package net.ajmyers.tm1.client.error;

import lombok.Getter;

/**
 * Something went wrong talking to TM1. The root of all the client's exceptions; unchecked.
 *
 * <ul>
 *   <li>{@link Tm1ApiException}: TM1 answered with an error.
 *   <li>{@link Tm1TransportException}: no complete answer, e.g. connection refused or timed out.
 *   <li>{@link Tm1ProtocolException}: an answer the client couldn't read, including errors
 *       that happened after TM1 had started a successful response.
 * </ul>
 */
public abstract sealed class Tm1Exception extends RuntimeException permits Tm1ApiException, Tm1TransportException, Tm1ProtocolException {

	private static final long serialVersionUID = 1L;

	/** The request's HTTP method, or {@code null} if not known. */
	@Getter
	private final String method;

	/** The request's URL, never including credentials, or {@code null} if not known. */
	@Getter
	private final String url;

	protected Tm1Exception(String message, String method, String url, Throwable cause) {
		super(message, cause);
		this.method = method;
		this.url = url;
	}

	/** Whether sending the same request again could succeed, such as after a timeout or a 503. */
	public abstract boolean isRetryable();

	/**
	 * Whether the request may have taken effect on TM1 even though it failed. False only when
	 * TM1 certainly didn't act on it, such as a connection that was never made, or a request it
	 * rejected. Check this before retrying anything that isn't idempotent, like running a process.
	 */
	public abstract boolean mayHaveApplied();

	static String describe(String method, String url) {
		return method == null ? "" : " (" + method + " " + url + ")";
	}
}
