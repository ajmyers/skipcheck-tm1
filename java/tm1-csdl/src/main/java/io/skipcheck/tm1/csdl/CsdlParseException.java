package io.skipcheck.tm1.csdl;

/** A CSDL document couldn't be parsed: it's malformed, or not CSDL. */
public class CsdlParseException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates an exception with a message.
	 * @param message what's wrong with the document
	 */
	public CsdlParseException(String message) {
		super(message);
	}

	/**
	 * Creates an exception with a message and the error that caused it.
	 * @param message what's wrong with the document
	 * @param cause the underlying XML or I/O error
	 */
	public CsdlParseException(String message, Throwable cause) {
		super(message, cause);
	}

}
