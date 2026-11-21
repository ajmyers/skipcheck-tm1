package net.ajmyers.tm1.client.error;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Works out a {@link Tm1ErrorKind} from an error response.
 *
 * <p>TM1's error codes rarely say what went wrong: code {@code 278} covers most failures. So
 * classification is a table of rules, tried in order, matching on HTTP status, code and
 * message. The first match wins, and the HTTP status decides when nothing matches. Matching on
 * message text is a last resort, but TM1 gives nothing better for several errors (impersonation
 * failures, for instance).
 *
 * <p>The table is data: {@link #defaults()} holds the rules this client knows, and
 * {@link #withRule} adds your own in front of them, e.g. for a message your TM1 version phrases
 * differently.
 */
public final class Tm1ErrorClassifier {

	private static final Tm1ErrorClassifier DEFAULTS = new Tm1ErrorClassifier(List.of(
			// Impersonation failures, recognized only by message (observed on 11.8)
			Rule.message("insufficient rights to impersonate", Tm1ErrorKind.IMPERSONATION_REFUSED),
			Rule.message("user specified in the impersonation request does not exist", Tm1ErrorKind.UNKNOWN_USER),
			Rule.message("tm1userexception: cancel", Tm1ErrorKind.CANCELLED),
			Rule.message("DataSpreadFailed", Tm1ErrorKind.DATA_SPREAD_FAILED),
			Rule.code("248", Tm1ErrorKind.INVALID_UNIQUE_NAME),
			Rule.code("65", Tm1ErrorKind.NO_READ_RIGHTS),
			Rule.message("ObjectSecurityNoReadRights", Tm1ErrorKind.NO_READ_RIGHTS),
			// 404 on 11.8: "'X' can not be found in collection of type 'Cube'."
			Rule.message("can ?not be found|not found|does not exist|doesn't exist|ObjectNotFound", Tm1ErrorKind.NOT_FOUND),
			Rule.message("already exists", Tm1ErrorKind.ALREADY_EXISTS),
			Rule.message("\\blocked\\b", Tm1ErrorKind.LOCKED),
			Rule.message("\\bin use\\b|is used by|isbeingusedby|cannot be deleted", Tm1ErrorKind.IN_USE)));

	private final List<Rule> rules;

	private Tm1ErrorClassifier(List<Rule> rules) {
		this.rules = List.copyOf(rules);
	}

	/** The rules this client knows about. */
	public static Tm1ErrorClassifier defaults() {
		return DEFAULTS;
	}

	/** A classifier trying {@code rule} before these rules. */
	public Tm1ErrorClassifier withRule(Rule rule) {
		List<Rule> next = new ArrayList<>();
		next.add(Objects.requireNonNull(rule, "rule"));
		next.addAll(rules);
		return new Tm1ErrorClassifier(next);
	}

	/** Classifies an error response; {@code error} may be null if the body wasn't an OData error. */
	public Tm1ErrorKind classify(int status, ODataError error) {
		for (Rule rule : rules) {
			if (rule.matches(status, error)) {
				return rule.kind();
			}
		}
		switch (status) {
			case 401:
				return Tm1ErrorKind.UNAUTHENTICATED;
			case 403:
				return Tm1ErrorKind.FORBIDDEN;
			case 404:
				return Tm1ErrorKind.NOT_FOUND;
			case 409:
				return Tm1ErrorKind.CONFLICT;
			case 412:
			case 428:
				return Tm1ErrorKind.PRECONDITION_FAILED;
			case 501:
				return Tm1ErrorKind.NOT_IMPLEMENTED;
			default:
				return Tm1ErrorKind.OTHER;
		}
	}

	/**
	 * One classification rule. Each condition that's set must match: the status if not null,
	 * the code exactly, and the message pattern (case-insensitive, anywhere in the message).
	 *
	 * @param status the HTTP status to match, or {@code null} for any
	 * @param code the error code to match, or {@code null} for any
	 * @param message a pattern to find in the message, or {@code null} for any
	 * @param kind the kind to report on a match
	 */
	public record Rule(Integer status, String code, Pattern message, Tm1ErrorKind kind) {

		public Rule {
			Objects.requireNonNull(kind, "kind");
			if (status == null && code == null && message == null) {
				throw new IllegalArgumentException("A rule needs at least one condition");
			}
		}

		/** A rule matching a message pattern, case-insensitively. */
		public static Rule message(String regex, Tm1ErrorKind kind) {
			return new Rule(null, null, Pattern.compile(regex, Pattern.CASE_INSENSITIVE), kind);
		}

		/** A rule matching an error code. */
		public static Rule code(String code, Tm1ErrorKind kind) {
			return new Rule(null, code, null, kind);
		}

		boolean matches(int actualStatus, ODataError error) {
			if (status != null && status != actualStatus) {
				return false;
			}
			if (code != null && (error == null || !code.equals(error.code()))) {
				return false;
			}
			return message == null || (error != null && message.matcher(error.message()).find());
		}
	}
}
