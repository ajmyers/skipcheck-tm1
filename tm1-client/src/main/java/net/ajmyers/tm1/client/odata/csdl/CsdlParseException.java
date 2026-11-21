package net.ajmyers.tm1.client.odata.csdl;

/** A CSDL document couldn't be parsed: it's malformed, or not CSDL. */
public class CsdlParseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public CsdlParseException(String message) {
		super(message);
	}

	public CsdlParseException(String message, Throwable cause) {
		super(message, cause);
	}
}
