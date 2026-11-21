package net.ajmyers.tm1.client.session;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;

import org.springframework.web.reactive.function.client.WebClientRequestException;

import io.netty.channel.ChannelException;
import net.ajmyers.tm1.client.error.Tm1Exception;
import net.ajmyers.tm1.client.error.Tm1TransportException;

/**
 * Maps failures below HTTP (connecting, TLS, timeouts, resets) to
 * {@link Tm1TransportException}.
 */
final class Transport {

	private Transport() {
	}

	/**
	 * {@code failure} as a {@link Tm1TransportException} if it's a failure below HTTP: a
	 * {@link WebClientRequestException}, an I/O error (resets and premature closes
	 * included), a Netty channel error or a timeout. Anything else, such as a
	 * {@link Tm1Exception} or a mistake in the request, is returned unchanged, so it
	 * isn't mistaken for a network failure.
	 *
	 * <p>
	 * The phase is {@code CONNECT} only when the cause shows the request was never sent:
	 * connect, DNS, TLS handshake and waiting for a pooled connection. Anything else may
	 * have reached TM1, so it's {@code RESPONSE}.
	 */
	static Throwable map(Throwable failure, String method, String url, boolean idempotent) {
		if (!isTransport(failure)) {
			return failure;
		}
		Throwable cause = failure instanceof WebClientRequestException && failure.getCause() != null
				? failure.getCause() : failure;
		Tm1TransportException.Phase phase = beforeSending(cause) ? Tm1TransportException.Phase.CONNECT
				: Tm1TransportException.Phase.RESPONSE;
		return new Tm1TransportException(phase, idempotent, method, url, cause);
	}

	private static boolean isTransport(Throwable failure) {
		return failure instanceof WebClientRequestException || failure instanceof IOException
				|| failure instanceof ChannelException || failure instanceof TimeoutException;
	}

	private static boolean beforeSending(Throwable failure) {
		// getCause() is null at the end of the chain; the bound guards against a cycle
		Throwable t = failure;
		for (int depth = 0; t != null && depth < 32; t = t.getCause(), depth++) {
			if (t instanceof ConnectException || t instanceof UnknownHostException
					|| t instanceof NoRouteToHostException || t instanceof SSLHandshakeException) {
				return true;
			}
			// Netty's connect timeout, and the pool's acquire timeout (a shaded class, so
			// matched by name)
			String name = t.getClass().getSimpleName();
			if (name.equals("ConnectTimeoutException") || name.equals("PoolAcquireTimeoutException")
					|| name.equals("PoolAcquirePendingLimitException")) {
				return true;
			}
		}
		return false;
	}

}
