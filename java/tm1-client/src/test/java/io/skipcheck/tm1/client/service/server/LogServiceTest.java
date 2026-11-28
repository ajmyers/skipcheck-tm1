// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.AuditLogEntry;
import io.skipcheck.tm1.client.model.ErrorLogFile;
import io.skipcheck.tm1.client.model.LogLevel;
import io.skipcheck.tm1.client.model.MessageLogEntry;
import io.skipcheck.tm1.client.model.TransactionLogEntry;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * LogService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/LogServiceTest.json}, which the
 * Python, .NET and TypeScript clients replay. A tail polls until it's cancelled, so its
 * cases take so many entries and then stop ({@link Conformance#take}).
 */
@SuppressWarnings("deprecation")
class LogServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	@Test
	void readsTheMessageLogEntries() {
		tm1()
			.json("{\"value\":[{\"ID\":0,\"TimeStamp\":\"0000-00-00T00:00Z\",\"Level\":\"Info\",\"Message\":\"first\"},"
					+ "{\"ID\":1,\"ThreadID\":7,\"TimeStamp\":\"2026-01-02T03:04:05.123Z\",\"Level\":\"Error\","
					+ "\"Logger\":\"TM1.Server\",\"Message\":\"x\"}]}");
		List<MessageLogEntry> entries = conformance.call("logs", "messageLogEntries",
				ODataQuery.none()
					.filter(Filter.raw("Level eq 'Error' or Level eq 'Info'"))
					.orderBy("TimeStamp")
					.top(2));
		assertThat(entries).extracting(MessageLogEntry::level).containsExactly(LogLevel.INFO, LogLevel.ERROR);
		assertThat(entries.get(0).timeStamp()).isNull();
		assertThat(tm1().last().target()).isEqualTo(
				"MessageLogEntries?$filter=Level%20eq%20'Error'%20or%20Level%20eq%20'Info'&$orderby=TimeStamp&$top=2");
	}

	@Test
	void readsTheMessageLogEitherWay() {
		tm1().json("{\"value\":[{\"ID\":0,\"Message\":\"newest\"}]}");
		List<MessageLogEntry> newest = conformance.call("logs", "messageLog", true, ODataQuery.none().top(1));
		assertThat(newest).extracting(MessageLogEntry::message).containsExactly("newest");
		assertThat(tm1().last().key()).isEqualTo("GET MessageLog(Reverse=true)?$top=1");

		tm1().json("{\"value\":[]}");
		conformance.call("logs", "messageLog", false, ODataQuery.none());
		assertThat(tm1().last().key()).isEqualTo("GET MessageLog(Reverse=false)");
	}

	@Test
	void readsTheTransactionLog() {
		tm1().json("{\"value\":[{\"ID\":0,\"Cube\":\"Sales\",\"User\":\"Admin\",\"OldValue\":1,\"NewValue\":2.5,"
				+ "\"Tuple\":[\"North\",\"Jan\"]},{\"ID\":1,\"StatusMessage\":\"CubeSerialized: Sales\"}]}");
		List<TransactionLogEntry> entries = conformance.call("logs", "transactionLogEntries",
				ODataQuery.none().filter(Filter.raw("Cube eq 'Sales'")));
		assertThat(entries).hasSize(2);
		assertThat(tm1().last().target()).isEqualTo("TransactionLogEntries?$filter=Cube%20eq%20'Sales'");

		tm1().json("{\"value\":[]}");
		conformance.call("logs", "transactionLog", true, false, ODataQuery.none().top(5));
		assertThat(tm1().last().target()).isEqualTo("TransactionLog(Reverse=true,IgnoreStatusMessages=false)?$top=5");

		tm1().json("{\"value\":[]}");
		conformance.call("logs", "transactionLog", false, true, ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("TransactionLog(Reverse=false,IgnoreStatusMessages=true)");
	}

	@Test
	void readsTheAuditLog() {
		tm1().json("{\"value\":[{\"ID\":0,\"UserName\":\"Admin\",\"Description\":\"Created\",\"ObjectType\":\"Cube\","
				+ "\"ObjectName\":\"Sales\",\"AuditDetails\":[]}]}");
		List<AuditLogEntry> entries = conformance.call("logs", "auditLogEntries",
				ODataQuery.none().expand("AuditDetails").orderBy("TimeStamp desc"));
		assertThat(entries).extracting(AuditLogEntry::objectName).containsExactly("Sales");
		assertThat(tm1().last().target()).isEqualTo("AuditLogEntries?$expand=AuditDetails&$orderby=TimeStamp%20desc");
	}

	@Test
	void readsErrorLogFiles() {
		tm1().json("{\"value\":[{\"Filename\":\"tm1processerror_20260102030405_Load Sales.log\"}]}");
		List<ErrorLogFile> files = conformance.call("logs", "errorLogFiles",
				ODataQuery.none().filter(Filter.raw("endswith(Filename,'_Load Sales.log')")));
		assertThat(files).extracting(ErrorLogFile::filename)
			.containsExactly("tm1processerror_20260102030405_Load Sales.log");

		tm1().json("{\"Filename\":\"tm1processerror_20260102030405_Load Sales.log\"}");
		conformance.call("logs", "errorLogFile", "tm1processerror_20260102030405_Load Sales.log",
				ODataQuery.none().select("Filename"));
		assertThat(tm1().last().target())
			.isEqualTo("ErrorLogFiles('tm1processerror_20260102030405_Load%20Sales.log')?$select=Filename");

		tm1().error(404, "278", "'x.log' can not be found");
		Throwable error = conformance.fail("logs", "errorLogFile", "x.log", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void readsAnErrorLogsTextWithoutItsByteOrderMark() {
		tm1().text("﻿Error: Prolog procedure line (3): x\n");
		String text = conformance.call("logs", "errorLogContent", "tm1processerror_x_Load.log");
		assertThat(text).isEqualTo("Error: Prolog procedure line (3): x\n");
		assertThat(tm1().last().key()).isEqualTo("GET ErrorLogFiles('tm1processerror_x_Load.log')/Content");

		tm1().text("no mark");
		assertThat((String) conformance.call("logs", "errorLogContent", "Joe's.log")).isEqualTo("no mark");
		assertThat(tm1().last().target()).isEqualTo("ErrorLogFiles('Joe''s.log')/Content");
	}

	@Test
	void tailsTheMessageLogSkippingWhatItHasEmitted() {
		tm1().json(
				"{\"@odata.context\":\"$metadata#Edm.String\",\"value\":\"MessageLogEntries/!delta('0[4096]38@11!19')\"}");
		tm1().json("{\"value\":[{\"ID\":0,\"Message\":\"a\"},{\"ID\":1,\"Message\":\"b\"}]}");
		tm1().json(
				"{\"value\":[{\"ID\":0,\"Message\":\"a\"},{\"ID\":1,\"Message\":\"b\"},{\"ID\":2,\"Message\":\"c\"}]}");
		List<MessageLogEntry> entries = conformance.take(3, "logs", "tailMessageLog", Duration.ZERO);
		assertThat(entries).extracting(MessageLogEntry::message).containsExactly("a", "b", "c");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("GET TailMessageLog()", "GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')",
					"GET MessageLogEntries/!delta('0%5B4096%5D38%4011!19')");
	}

	@Test
	void stopsTailingMidPoll() {
		tm1().json("{\"value\":\"MessageLogEntries/!delta('4')\"}");
		tm1().json("{\"value\":[{\"ID\":0,\"Message\":\"a\"},{\"ID\":1,\"Message\":\"b\"}]}");
		List<MessageLogEntry> entries = conformance.take(1, "logs", "tailMessageLog", Duration.ofSeconds(30));
		assertThat(entries).extracting(MessageLogEntry::message).containsExactly("a");
		assertThat(tm1().requests()).hasSize(2);
	}

	@Test
	void tailsThroughAnEmptyPoll() {
		tm1().json("{\"value\":\"MessageLogEntries/!delta('2')\"}");
		tm1().json("{\"value\":[]}");
		tm1().json("{\"value\":[{\"ID\":0,\"Message\":\"a\"}]}");
		List<MessageLogEntry> entries = conformance.take(1, "logs", "tailMessageLog", Duration.ofMillis(1));
		assertThat(entries).extracting(MessageLogEntry::message).containsExactly("a");
	}

	@Test
	void tailsTheTransactionAndAuditLogs() {
		tm1().json("{\"value\":\"TransactionLogEntries/!delta('1')\"}");
		tm1().json("{\"value\":[{\"ID\":0,\"Cube\":\"Sales\",\"NewValue\":1}]}");
		List<TransactionLogEntry> changes = conformance.take(1, "logs", "tailTransactionLog", Duration.ZERO);
		assertThat(changes).extracting(TransactionLogEntry::cube).containsExactly("Sales");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::key)
			.containsExactly("GET TailTransactionLog()", "GET TransactionLogEntries/!delta('1')");

		tm1().json("{\"value\":\"AuditLogEntries/!delta('3')\"}");
		tm1().json("{\"value\":[{\"ID\":0,\"ObjectName\":\"Sales\"},{\"ID\":1,\"ObjectName\":\"Region\"}]}");
		List<AuditLogEntry> audits = conformance.take(2, "logs", "tailAuditLog", Duration.ZERO);
		assertThat(audits).extracting(AuditLogEntry::objectName).containsExactly("Sales", "Region");
	}

	@Test
	void aTailThatFailsFails() {
		tm1().error(500, "278", "Internal error");
		conformance.fail("logs", "tailMessageLog", Duration.ZERO);
	}

	@Test
	void refusesAMissingFilename() {
		Throwable error = conformance.fail("logs", "errorLogContent", (Object) null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}
