package net.ajmyers.tm1.client.error;

/** TM1 doesn't implement the request (501), such as {@code $search}. */
public final class Tm1NotImplementedException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1NotImplementedException(int status, Tm1ErrorKind kind, ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}
}
