// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Hierarchy;
import io.skipcheck.tm1.client.model.Level;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Levels: {@code /Dimensions('d')/Hierarchies('h')/Levels}, keyed by number, and their
 * members. Read only: TM1 derives levels from the hierarchy's structure.
 *
 * <p>
 * List a hierarchy's levels, with how many members each has:
 * {@snippet class = examples.LevelServiceExamples region = list}
 *
 * <p>
 * Read a level's members. Levels are numbered from the top, so level 0 holds the roots:
 * {@snippet class = examples.LevelServiceExamples region = members}
 */
public final class LevelService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public LevelService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET …/Hierarchies('h')/Levels}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select, expand, filter and order
	 * @return the hierarchy's levels
	 */
	@Tm1Endpoint("Navigation Hierarchy/Levels")
	public Flux<Level> list(String dimension, String hierarchy, ODataQuery query) {
		return session.stream(Tm1Request.list(levels(dimension, hierarchy), query, Level.class));
	}

	/**
	 * {@code GET …/Levels(n)}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param number the level's number, from 0
	 * @param query what to select and expand
	 * @return the level, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Hierarchy/Levels")
	public Mono<Level> get(String dimension, String hierarchy, int number, ODataQuery query) {
		return session.execute(Tm1Request.get(levels(dimension, hierarchy).key(number), query, Level.class));
	}

	/**
	 * {@code GET …/Levels(n)/Members}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param number the level's number
	 * @param query what to select, expand, filter and order
	 * @return the level's members
	 */
	@Tm1Endpoint("Navigation Level/Members")
	public Flux<Member> members(String dimension, String hierarchy, int number, ODataQuery query) {
		return session
			.stream(Tm1Request.list(levels(dimension, hierarchy).key(number).nav("Members"), query, Member.class));
	}

	/**
	 * {@code GET …/Levels(n)/Hierarchy}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param number the level's number
	 * @param query what to select and expand
	 * @return the level's hierarchy
	 */
	@Tm1Endpoint("Navigation Level/Hierarchy")
	public Mono<Hierarchy> hierarchy(String dimension, String hierarchy, int number, ODataQuery query) {
		return session
			.execute(Tm1Request.get(levels(dimension, hierarchy).key(number).nav("Hierarchy"), query, Hierarchy.class));
	}

	private static ODataPath levels(String dimension, String hierarchy) {
		return ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Levels");
	}

}
