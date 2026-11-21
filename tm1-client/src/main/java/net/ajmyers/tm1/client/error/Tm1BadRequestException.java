package net.ajmyers.tm1.client.error;

/** 400-range errors not covered by another type, e.g. invalid MDX or a refused cell write. */
public final class Tm1BadRequestException extends Tm1ApiException {

	private static final long serialVersionUID = 1L;

	Tm1BadRequestException(int status, Tm1ErrorKind kind, ODataError error, String method, String url) {
		super(status, kind, error, method, url);
	}
}
