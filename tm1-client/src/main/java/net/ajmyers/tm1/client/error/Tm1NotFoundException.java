package net.ajmyers.tm1.client.error;

/** The object doesn't exist, whether TM1 said 404 or 400. */
public final class Tm1NotFoundException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1NotFoundException(int status, Tm1ErrorKind kind, ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}
}
