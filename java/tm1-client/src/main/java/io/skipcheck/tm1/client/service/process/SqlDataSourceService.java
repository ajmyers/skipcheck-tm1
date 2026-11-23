// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.process;

import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.SQLDataSource;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The ODBC data sources on TM1's host, which processes can read from
 * ({@code DataSource.Type} {@code ODBC}). They're the host's ODBC configuration, not
 * TM1's: read only here.
 *
 * <p>
 * List the data sources:
 * {@snippet class = examples.SqlDataSourceServiceExamples region = names}
 */
public final class SqlDataSourceService {

	private static final ODataPath SOURCES = ODataPath.of("SQLDataSources");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public SqlDataSourceService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET SQLDataSources}, streamed.
	 * @param query what to select, filter and order
	 * @return the data sources: their names and descriptions
	 */
	@Tm1Endpoint("EntitySet SQLDataSources")
	public Flux<SQLDataSource> list(ODataQuery query) {
		return session.stream(Tm1Request.list(SOURCES, query, SQLDataSource.class));
	}

	/**
	 * {@code GET SQLDataSources('name')}.
	 * @param name the data source's name
	 * @param query what to select
	 * @return the data source, or a {@code Tm1NotFoundException} if the host has none by
	 * that name
	 */
	@Tm1Endpoint("EntitySet SQLDataSources")
	public Mono<SQLDataSource> get(String name, ODataQuery query) {
		return session
			.execute(Tm1Request.get(SOURCES.key(Objects.requireNonNull(name, "name")), query, SQLDataSource.class));
	}

}
