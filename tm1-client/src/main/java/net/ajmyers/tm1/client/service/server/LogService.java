package net.ajmyers.tm1.client.service.server;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.AuditLogEntry;
import net.ajmyers.tm1.client.model.ErrorLogFile;
import net.ajmyers.tm1.client.model.MessageLogEntry;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.model.TransactionLogEntry;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

/**
 * The server's logs: the message log, the transaction log (cell changes), the audit log
 * (changes to objects and security), and the error logs processes write.
 *
 * <p>
 * What TM1 (11.8) does here that you might not expect:
 * <ul>
 * <li>An entry's {@code ID} numbers it within the response, not in the log: the same
 * entry gets another ID in the next request, and TM1 refuses to fetch an entry by its ID
 * (501, "Indexing of message log entries is not supported"). So there's no get by ID
 * here.</li>
 * <li>A message log entry without a time, such as the log's first line, has the
 * {@code TimeStamp} {@code 0000-00-00T00:00Z}; it's read as {@code null}.</li>
 * <li>The transaction and audit logs only fill when they're switched on (a cube's
 * logging, and {@code AuditLog} in the configuration).</li>
 * </ul>
 *
 * <p>
 * Read the newest message log entries:
 * {@snippet class = examples.LogServiceExamples region = newest}
 *
 * <p>
 * Follow the message log for errors:
 * {@snippet class = examples.LogServiceExamples region = follow}
 *
 * <p>
 * Read a process's error logs:
 * {@snippet class = examples.LogServiceExamples region = process-errors}
 *
 * <p>
 * Count the cell changes in the transaction log:
 * {@snippet class = examples.LogServiceExamples region = changes}
 */
public final class LogService {

	private static final ODataPath ERROR_LOG_FILES = ODataPath.of("ErrorLogFiles");

	private static final String BYTE_ORDER_MARK = "\uFEFF";

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public LogService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET MessageLogEntries}, streamed, oldest first unless ordered otherwise.
	 * @param query what to select, filter (such as on {@code Level} or
	 * {@code TimeStamp}), order and how many
	 * @return the entries
	 */
	@Tm1Endpoint("EntitySet MessageLogEntries")
	public Flux<MessageLogEntry> messageLogEntries(ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.of("MessageLogEntries"), query, MessageLogEntry.class));
	}

	/**
	 * {@code MessageLog(Reverse=…)}: the message log, streamed, newest first if
	 * {@code reverse}.
	 * @param reverse whether to start with the newest entry
	 * @param query what to select, filter and how many
	 * @return the entries
	 */
	@Tm1Endpoint("FunctionImport MessageLog")
	public Flux<MessageLogEntry> messageLog(boolean reverse, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.MESSAGE_LOG;
		return session.stream(
				Tm1Request
					.list(ODataPath.functionImport(importName(operation), Map.of("Reverse", reverse)), query,
							MessageLogEntry.class)
					.requires(operation.capability()));
	}

	/**
	 * {@code GET TransactionLogEntries}, streamed. Besides cell changes, the log holds
	 * status lines (such as {@code CubeSerialized: …}) with only {@code StatusMessage}
	 * set.
	 *
	 * <p>
	 * TM1 (11.8) logs a password being set as a change of the {@code PASSWORD} cell of
	 * {@code }ClientProperties}, and writes the new value, the password's hash, as raw
	 * bytes that aren't UTF-8. They read as U+FFFD, the replacement character, here and
	 * in the other transaction log methods.
	 * @param query what to select, filter (such as on {@code Cube}), order and how many
	 * @return the entries
	 */
	@Tm1Endpoint("EntitySet TransactionLogEntries")
	public Flux<TransactionLogEntry> transactionLogEntries(ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.of("TransactionLogEntries"), query, TransactionLogEntry.class));
	}

	/**
	 * {@code TransactionLog(Reverse=…,IgnoreStatusMessages=…)}: the transaction log,
	 * streamed.
	 * @param reverse whether to start with the newest entry
	 * @param ignoreStatusMessages whether to leave out status lines, keeping cell changes
	 * @param query what to select, filter and how many
	 * @return the entries
	 */
	@Tm1Endpoint("FunctionImport TransactionLog")
	public Flux<TransactionLogEntry> transactionLog(boolean reverse, boolean ignoreStatusMessages, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.TRANSACTION_LOG;
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("Reverse", reverse);
		parameters.put("IgnoreStatusMessages", ignoreStatusMessages);
		return session.stream(Tm1Request
			.list(ODataPath.functionImport(importName(operation), parameters), query, TransactionLogEntry.class)
			.requires(operation.capability()));
	}

	/**
	 * {@code GET AuditLogEntries}, streamed; empty unless the audit log is on.
	 * @param query what to select, filter, order, and expand ({@code AuditDetails}, the
	 * entry's details)
	 * @return the entries
	 */
	@Tm1Endpoint("EntitySet AuditLogEntries")
	@Tm1Endpoint("Navigation AuditLogEntry/AuditDetails")
	public Flux<AuditLogEntry> auditLogEntries(ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.of("AuditLogEntries"), query, AuditLogEntry.class));
	}

	/**
	 * Follows the message log: the entries written from the moment of subscribing,
	 * polling every {@code every} until cancelled. Built on {@code TailMessageLog()}
	 * (schema 11.3.0).
	 *
	 * <p>
	 * {@code TailMessageLog()} returns a delta link, but TM1 (11.8) answers it with every
	 * entry since the link was made, each time, and with no next link. So this keeps the
	 * one link and skips the entries it has already emitted, and each poll fetches more
	 * the longer it runs. For a long watch, subscribe afresh now and then.
	 * @param every how long to wait between polls
	 * @return the new entries, as they come
	 */
	@Tm1Endpoint("FunctionImport TailMessageLog")
	public Flux<MessageLogEntry> tailMessageLog(Duration every) {
		return tail(Tm1Operations.TAIL_MESSAGE_LOG, MessageLogEntry.class, every);
	}

	/**
	 * Follows the transaction log, as {@link #tailMessageLog} does the message log. Built
	 * on {@code TailTransactionLog()} (schema 11.3.0).
	 * @param every how long to wait between polls
	 * @return the new entries, as they come
	 */
	@Tm1Endpoint("FunctionImport TailTransactionLog")
	public Flux<TransactionLogEntry> tailTransactionLog(Duration every) {
		return tail(Tm1Operations.TAIL_TRANSACTION_LOG, TransactionLogEntry.class, every);
	}

	/**
	 * Follows the audit log, as {@link #tailMessageLog} does the message log. Built on
	 * {@code TailAuditLog()} (schema 11.6.0).
	 * @param every how long to wait between polls
	 * @return the new entries, as they come
	 */
	@Tm1Endpoint("FunctionImport TailAuditLog")
	public Flux<AuditLogEntry> tailAuditLog(Duration every) {
		return tail(Tm1Operations.TAIL_AUDIT_LOG, AuditLogEntry.class, every);
	}

	/**
	 * {@code GET ErrorLogFiles}, streamed: the error logs processes have written, such as
	 * {@code tm1processerror_<time>_<process>.log}.
	 * @param query what to select, filter and order
	 * @return the files
	 */
	@Tm1Endpoint("EntitySet ErrorLogFiles")
	public Flux<ErrorLogFile> errorLogFiles(ODataQuery query) {
		return session.stream(Tm1Request.list(ERROR_LOG_FILES, query, ErrorLogFile.class));
	}

	/**
	 * {@code GET ErrorLogFiles('name')}.
	 * @param filename the file's name
	 * @param query what to select
	 * @return the file, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet ErrorLogFiles")
	public Mono<ErrorLogFile> errorLogFile(String filename, ODataQuery query) {
		return session.execute(Tm1Request.get(ERROR_LOG_FILES.key(Objects.requireNonNull(filename, "filename")), query,
				ErrorLogFile.class));
	}

	/**
	 * {@code GET ErrorLogFiles('name')/Content}: what the file says, as text, read whole.
	 * TM1 (11.8) starts it with a byte order mark, which is dropped.
	 * @param filename the file's name
	 * @return the text
	 */
	@Tm1Endpoint("EntitySet ErrorLogFiles")
	public Mono<String> errorLogContent(String filename) {
		return session
			.execute(Tm1Request.text(ERROR_LOG_FILES.key(Objects.requireNonNull(filename, "filename")).nav("Content")))
			.map(text -> text.startsWith(BYTE_ORDER_MARK) ? text.substring(1) : text);
	}

	private <T> Flux<T> tail(Tm1Operation operation, Class<T> type, Duration every) {
		Objects.requireNonNull(every, "every");
		return session
			.execute(Tm1Request
				.get(ODataPath.functionImport(importName(operation), Map.of()), ODataQuery.none(), JsonNode.class)
				.requires(operation.capability()))
			.map(link -> link.path("value").asString())
			.flatMapMany(link -> {
				AtomicLong seen = new AtomicLong();
				return Flux
					.defer(() -> session.stream(Tm1Request.list(ODataPath.link(link), ODataQuery.none(), type))
						.skip(seen.get())
						.doOnNext(entry -> seen.incrementAndGet()))
					.repeatWhen(polls -> polls.concatMap(poll -> Mono.delay(every)));
			});
	}

	private static String importName(Tm1Operation operation) {
		return Objects.requireNonNull(operation.importName(), operation.name());
	}

}
