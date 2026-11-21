package net.ajmyers.tm1.client.session;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLHandshakeException;

import org.springframework.web.reactive.function.client.WebClientRequestException;

import net.ajmyers.tm1.client.error.Tm1Exception;
import net.ajmyers.tm1.client.error.Tm1TransportException;

/** Maps failures below HTTP (connecting, TLS, timeouts, resets) to {@link Tm1TransportException}. */
final class Transport {

	private Transport() {
	}

	/**
	 * {@code failure} as a {@link Tm1Exception}: unchanged if it already is one, otherwise a
	 * transport failure. The phase is {@code CONNECT} only when the cause shows the request was
	 * never sent: connect, DNS, TLS handshake and waiting for a pooled connection. Anything else
	 * may have reached TM1, so it's {@code RESPONSE}.
	 */
	static Throwable map(Throwable failure, String method, String url, boolean idempotent) {
		if (failure instanceof Tm1Exception || failure instanceof Tm1SessionClosedException) {
			return failure;
		}
		Throwable cause = failure instanceof WebClientRequestException && failure.getCause() != null ? failure.getCause() : failure;
		Tm1TransportException.Phase phase = beforeSending(cause) ? Tm1TransportException.Phase.CONNECT : Tm1TransportException.Phase.RESPONSE;
		return new Tm1TransportException(phase, idempotent, method, url, cause);
	}

	private static boolean beforeSending(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof ConnectException || t instanceof UnknownHostException || t instanceof NoRouteToHostException
					|| t instanceof SSLHandshakeException) {
				return true;
			}
			// Netty's connect timeout, and the pool's acquire timeout (a shaded class, so matched by name)
			String name = t.getClass().getSimpleName();
			if (name.equals("ConnectTimeoutException") || name.equals("PoolAcquireTimeoutException")
					|| name.equals("PoolAcquirePendingLimitException")) {
				return true;
			}
		}
		return false;
	}
}
