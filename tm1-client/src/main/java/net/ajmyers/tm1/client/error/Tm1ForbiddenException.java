package net.ajmyers.tm1.client.error;

/** Not allowed: no rights, or an impersonation TM1 refused. */
public final class Tm1ForbiddenException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1ForbiddenException(int status, Tm1ErrorKind kind, ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}

}
