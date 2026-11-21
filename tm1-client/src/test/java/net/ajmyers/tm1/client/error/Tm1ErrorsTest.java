package net.ajmyers.tm1.client.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Classification of error responses captured from TM1 11.8.03500.6, plus messages seen in
 * production use of 11.8.
 */
class Tm1ErrorsTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@Test
	void readsTheOdataErrorBody() {
		ODataError e = error(
				"{\"error\":{\"code\":\"278\",\"message\":\"Boom\",\"target\":\"Name\",\"details\":[{\"code\":\"1\",\"message\":\"inner\"}]}}");
		assertThat(e.code()).isEqualTo("278");
		assertThat(e.message()).isEqualTo("Boom");
		assertThat(e.target()).isEqualTo("Name");
		assertThat(e.details()).extracting(ODataError::message).containsExactly("inner");
		assertThat(ODataError.from(MAPPER.readTree("{\"value\":[]}"))).isNull();
		assertThat(ODataError.from(MAPPER.readTree("[]"))).isNull();
	}

	/**
	 * TM1 11.8 sends impersonation errors as plain text: a status line, then the message.
	 */
	@Test
	void readsAndClassifiesPlainTextErrors() {
		ODataError unknown = ODataError
			.fromText("Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n");
		assertThat(unknown).isNotNull();
		assertThat(unknown.message()).isEqualTo("The user specified in the impersonation request does not exist.");
		assertThat(unknown.code()).isEmpty();
		Tm1ErrorClassifier classifier = Tm1ErrorClassifier.defaults();
		assertThat(classifier.classify(400, unknown)).isEqualTo(Tm1ErrorKind.UNKNOWN_USER);
		assertThat(classifier.classify(403,
				ODataError.fromText(
						"Error: Forbidden\r\nYou have insufficient rights to impersonate the user specified!\r\n")))
			.isEqualTo(Tm1ErrorKind.IMPERSONATION_REFUSED);
		assertThat(classifier.classify(403, ODataError.fromText(
				"Error: Forbidden\r\nYou need to be a (data-)administrator to be able to impersonate another user.\r\n")))
			.isEqualTo(Tm1ErrorKind.IMPERSONATION_REFUSED);
		assertThat(ODataError.fromText("Error: Unauthorized\r\n")).isNull();
		assertThat(ODataError.fromText("x".repeat(5000)).message()).hasSize(1001);
	}

	@Test
	void classifiesCapturedResponses() {
		// GET Cubes('NoSuchCube')
		assertThat(api(404,
				"{\"error\":{\"code\":\"278\",\"message\":\"'NoSuchCube' can not be found in collection of type 'Cube'.\"}}"))
			.isInstanceOf(Tm1NotFoundException.class)
			.satisfies(e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.NOT_FOUND))
			.hasMessage(
					"TM1 returned 404: 'NoSuchCube' can not be found in collection of type 'Cube'. [code 278] (GET Cubes('NoSuchCube'))");
		// $search
		assertThat(api(501, "{\"error\":{\"code\":\"278\",\"message\":\"$search not implemented.\"}}"))
			.isInstanceOf(Tm1NotImplementedException.class);
		// PATCH with a stale If-Match
		assertThat(api(412, "{\"error\":{\"code\":\"278\",\"message\":\"\"}}"))
			.isInstanceOf(Tm1PreconditionFailedException.class);
		// Multipart $batch
		assertThat(api(400,
				"{\"error\":{\"code\":\"278\",\"message\":\"The content type specified is not supported. Please use JSON (application/json) as the content type.\"}}"))
			.isInstanceOf(Tm1BadRequestException.class)
			.satisfies(e -> assertThat(e.kind()).isEqualTo(Tm1ErrorKind.OTHER));
	}

	@Test
	void classifiesKnownMessages() {
		assertThat(api(403, msg("You have insufficient rights to impersonate the user specified!")).kind())
			.isEqualTo(Tm1ErrorKind.IMPERSONATION_REFUSED);
		assertThat(api(400, msg("The user specified in the impersonation request does not exist")).kind())
			.isEqualTo(Tm1ErrorKind.UNKNOWN_USER);
		assertThat(api(400, msg("TM1UserException: Cancel")).kind()).isEqualTo(Tm1ErrorKind.CANCELLED);
		assertThat(api(400, msg("DataSpreadFailed")).kind()).isEqualTo(Tm1ErrorKind.DATA_SPREAD_FAILED);
		assertThat(api(400,
				"{\"error\":{\"code\":\"248\",\"message\":\"Invalid element unique name specified in dimensional context\"}}")
			.kind()).isEqualTo(Tm1ErrorKind.INVALID_UNIQUE_NAME);
		assertThat(api(400, msg("Dimension 'Region' already exists"))).isInstanceOf(Tm1ConflictException.class);
		assertThat(api(400, msg("ObjectNotFound"))).isInstanceOf(Tm1NotFoundException.class);
		assertThat(api(400, msg("Object is locked by another user")).kind()).isEqualTo(Tm1ErrorKind.LOCKED);
		assertThat(api(400, msg("Subset is used by a view and cannot be deleted")).kind())
			.isEqualTo(Tm1ErrorKind.IN_USE);
		// "Blocked" isn't "locked"
		assertThat(api(400, msg("Blocked")).kind()).isEqualTo(Tm1ErrorKind.OTHER);
	}

	@Test
	void fallsBackOnTheStatus() {
		assertThat(api(401, "")).isInstanceOf(Tm1AuthenticationException.class);
		assertThat(api(403, "")).isInstanceOf(Tm1ForbiddenException.class);
		assertThat(api(409, "")).isInstanceOf(Tm1ConflictException.class);
		assertThat(api(428, "")).isInstanceOf(Tm1PreconditionFailedException.class);
		assertThat(api(503, "")).isInstanceOf(Tm1ServerException.class);
		assertThat(api(418, "")).isInstanceOf(Tm1BadRequestException.class);
		assertThat(Tm1ApiException.of(302, null, "GET", "x", Tm1ErrorClassifier.defaults()).getClass())
			.isEqualTo(Tm1ApiException.class);
	}

	@Test
	void letsCallersAddRules() {
		Tm1ErrorClassifier custom = Tm1ErrorClassifier.defaults()
			.withRule(Tm1ErrorClassifier.Rule.message("frozen", Tm1ErrorKind.LOCKED));
		assertThat(custom.classify(400, ODataError.from(MAPPER.readTree(msg("Cube is frozen")))))
			.isEqualTo(Tm1ErrorKind.LOCKED);
		assertThat(Tm1ErrorClassifier.defaults().classify(400, ODataError.from(MAPPER.readTree(msg("Cube is frozen")))))
			.isEqualTo(Tm1ErrorKind.OTHER);
	}

	@Test
	void saysWhetherARetryCouldHelpAndWhetherTheRequestMayHaveApplied() {
		assertThat(api(503, "").isRetryable()).isTrue();
		assertThat(api(503, "").mayHaveApplied()).isTrue();
		assertThat(api(400, "").isRetryable()).isFalse();
		assertThat(api(400, "").mayHaveApplied()).isFalse();
		assertThat(api(501, "").mayHaveApplied()).isFalse();

		var connect = new Tm1TransportException(Tm1TransportException.Phase.CONNECT, false, "POST", "x", null);
		assertThat(connect.isRetryable()).isTrue();
		assertThat(connect.mayHaveApplied()).isFalse();
		var postTimedOut = new Tm1TransportException(Tm1TransportException.Phase.RESPONSE, false, "POST", "x", null);
		assertThat(postTimedOut.isRetryable()).isFalse();
		assertThat(postTimedOut.mayHaveApplied()).isTrue();
		assertThat(
				new Tm1TransportException(Tm1TransportException.Phase.RESPONSE, true, "GET", "x", null).isRetryable())
			.isTrue();

		var protocol = new Tm1ProtocolException("bad", null, "GET", "x", null);
		assertThat(protocol.isRetryable()).isFalse();
		assertThat(protocol.mayHaveApplied()).isTrue();
	}

	private static Tm1ApiException api(int status, String body) {
		return Tm1ApiException.of(status, body.isEmpty() ? null : error(body), "GET", "Cubes('NoSuchCube')",
				Tm1ErrorClassifier.defaults());
	}

	private static ODataError error(String json) {
		return ODataError.from(MAPPER.readTree(json));
	}

	private static String msg(String message) {
		return "{\"error\":{\"code\":\"278\",\"message\":\"" + message + "\"}}";
	}

}
