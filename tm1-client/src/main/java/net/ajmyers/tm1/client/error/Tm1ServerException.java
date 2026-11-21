package net.ajmyers.tm1.client.error;

/** TM1 failed while handling the request (5xx). */
public final class Tm1ServerException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1ServerException(int status, Tm1ErrorKind kind, ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}

}
