package net.ajmyers.tm1.client.session;

import java.time.Duration;

import lombok.Builder;

/**
 * How long the session waits, by kind of work (plan §16.2). TM1 answers some calls only when the
 * work is done, so one timeout for everything is either too short for processes or too long for
 * a login.
 *
 * <pre>{@code
 * Tm1Timeouts timeouts = Tm1Timeouts.builder().request(Duration.ofHours(2)).build();
 * }</pre>
 *
 * <p>Unset values take the defaults below.
 *
 * @param connect how long to wait for a connection, including TLS. Default 10 s.
 * @param login how long to wait for a login response. Short, because a TM1 that is still loading
 *     accepts connections and never answers. Default 30 s.
 * @param request how long a request may go without receiving any data, unless the request sets
 *     its own {@linkplain net.ajmyers.tm1.client.Tm1Request#withTimeout timeout}. This is an idle
 *     timeout, not a limit on the whole response, so a large collection that keeps streaming isn't
 *     cut off. Default 10 min.
 * @param close how long to wait for TM1 to close the session. Default 5 s.
 */
@Builder(toBuilder = true)
public record Tm1Timeouts(Duration connect, Duration login, Duration request, Duration close) {

	/** The defaults. */
	public static final Tm1Timeouts DEFAULTS = Tm1Timeouts.builder().build();

	public Tm1Timeouts {
		connect = positive(connect, Duration.ofSeconds(10), "connect");
		login = positive(login, Duration.ofSeconds(30), "login");
		request = positive(request, Duration.ofMinutes(10), "request");
		close = positive(close, Duration.ofSeconds(5), "close");
	}

	private static Duration positive(Duration value, Duration fallback, String name) {
		if (value == null) {
			return fallback;
		}
		if (value.isNegative() || value.isZero()) {
			throw new IllegalArgumentException(name + " timeout must be positive: " + value);
		}
		return value;
	}
}
