// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.dimension;

import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Element;
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
 * Members: {@code /Dimensions('d')/Hierarchies('h')/Members}, the MDX view of a
 * hierarchy's elements, and a member's parent, children, element, level and hierarchy.
 * Read only: members change through their elements.
 *
 * <p>
 * Read a member's unique name, to use in MDX:
 * {@snippet class = examples.MemberServiceExamples region = unique-name}
 *
 * <p>
 * Read a member's children:
 * {@snippet class = examples.MemberServiceExamples region = children}
 *
 * <p>
 * Read a member's parent, which a root doesn't have:
 * {@snippet class = examples.MemberServiceExamples region = parent}
 */
public final class MemberService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public MemberService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET …/Hierarchies('h')/Members}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param query what to select, expand, filter and order
	 * @return the hierarchy's members
	 */
	@Tm1Endpoint("Navigation Hierarchy/Members")
	public Flux<Member> list(String dimension, String hierarchy, ODataQuery query) {
		return session.stream(Tm1Request.list(members(dimension, hierarchy), query, Member.class));
	}

	/**
	 * {@code GET …/Members('m')}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @param query what to select and expand
	 * @return the member, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Hierarchy/Members")
	public Mono<Member> get(String dimension, String hierarchy, String member, ODataQuery query) {
		return session.execute(Tm1Request.get(member(dimension, hierarchy, member), query, Member.class));
	}

	/**
	 * {@code GET …/Members('m')/Parent}. A member with several parents has the first.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @param query what to select and expand
	 * @return the member's parent, or empty for a root
	 */
	@Tm1Endpoint("Navigation Member/Parent")
	public Mono<Member> parent(String dimension, String hierarchy, String member, ODataQuery query) {
		return session.execute(Tm1Request.get(member(dimension, hierarchy, member).nav("Parent"), query, Member.class));
	}

	/**
	 * {@code GET …/Members('m')/Children}, streamed.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @param query what to select, expand, filter and order
	 * @return the member's children, in order
	 */
	@Tm1Endpoint("Navigation Member/Children")
	public Flux<Member> children(String dimension, String hierarchy, String member, ODataQuery query) {
		return session
			.stream(Tm1Request.list(member(dimension, hierarchy, member).nav("Children"), query, Member.class));
	}

	/**
	 * {@code GET …/Members('m')/Element}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @param query what to select and expand
	 * @return the element the member stands for
	 */
	@Tm1Endpoint("Navigation Member/Element")
	public Mono<Element> element(String dimension, String hierarchy, String member, ODataQuery query) {
		return session
			.execute(Tm1Request.get(member(dimension, hierarchy, member).nav("Element"), query, Element.class));
	}

	/**
	 * {@code GET …/Members('m')/Level}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @param query what to select and expand
	 * @return the member's level
	 */
	@Tm1Endpoint("Navigation Member/Level")
	public Mono<Level> level(String dimension, String hierarchy, String member, ODataQuery query) {
		return session.execute(Tm1Request.get(member(dimension, hierarchy, member).nav("Level"), query, Level.class));
	}

	/**
	 * {@code GET …/Members('m')/Hierarchy}.
	 * @param dimension the dimension's name
	 * @param hierarchy the hierarchy's name
	 * @param member the member's name
	 * @param query what to select and expand
	 * @return the member's hierarchy
	 */
	@Tm1Endpoint("Navigation Member/Hierarchy")
	public Mono<Hierarchy> hierarchy(String dimension, String hierarchy, String member, ODataQuery query) {
		return session
			.execute(Tm1Request.get(member(dimension, hierarchy, member).nav("Hierarchy"), query, Hierarchy.class));
	}

	private static ODataPath members(String dimension, String hierarchy) {
		return ODataPath.of("Dimensions")
			.key(Objects.requireNonNull(dimension, "dimension"))
			.nav("Hierarchies")
			.key(Objects.requireNonNull(hierarchy, "hierarchy"))
			.nav("Members");
	}

	private static ODataPath member(String dimension, String hierarchy, String member) {
		return members(dimension, hierarchy).key(Objects.requireNonNull(member, "member"));
	}

}
