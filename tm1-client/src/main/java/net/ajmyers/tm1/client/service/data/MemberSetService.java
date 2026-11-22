package net.ajmyers.tm1.client.service.data;

import java.util.Map;
import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.CellsetAxis;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Member sets: the results of MDX set expressions, as a single axis of tuples, kept on
 * the server in {@code /MemberSets} and keyed by an ordinal TM1 assigns (1, 2, … in the
 * session).
 *
 * <p>
 * Like a cellset, a member set stays until it's deleted or the session ends. TM1 (11.8)
 * lists none, though each reads by its ordinal: keep the ordinals {@link #execute}
 * returns, and delete what you're done with.
 */
public final class MemberSetService {

	private static final ODataPath MEMBER_SETS = ODataPath.of("MemberSets");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public MemberSetService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code ExecuteMDXSetExpression}: evaluates an MDX set expression, such as
	 * {@code {TM1SUBSETALL([Region].[Region])}}, keeping the result as a member set.
	 * Expand {@code Tuples($expand=Members($select=Name))} to get its members in the same
	 * round trip. Needs schema 11.1.0.
	 * @param mdx the set expression
	 * @param query what to expand of the result
	 * @return the set, with the {@code Ordinal} that keys it in {@code MemberSets}
	 */
	@Tm1Endpoint("ActionImport ExecuteMDXSetExpression")
	public Mono<CellsetAxis> execute(String mdx, ODataQuery query) {
		Tm1Operation operation = Tm1Operations.EXECUTE_MDX_SET_EXPRESSION;
		return session.execute(Tm1Request
			.post(ODataPath.of(Objects.requireNonNull(operation.importName())),
					Map.of("MDX", Objects.requireNonNull(mdx, "mdx")), CellsetAxis.class)
			.withQuery(query)
			.requires(operation.capability()));
	}

	/**
	 * {@code GET MemberSets}, streamed. TM1 (11.8) lists none, though each reads by its
	 * ordinal with {@link #get}.
	 * @param query what to select and expand
	 * @return the member sets TM1 lists
	 */
	@Tm1Endpoint("EntitySet MemberSets")
	public Flux<CellsetAxis> list(ODataQuery query) {
		return session.stream(Tm1Request.list(MEMBER_SETS, query, CellsetAxis.class));
	}

	/**
	 * {@code GET MemberSets(n)}.
	 * @param ordinal the set's ordinal, from {@link #execute}
	 * @param query what to expand, such as {@code Tuples($expand=Members)}
	 * @return the set, or a {@code Tm1NotFoundException} if it's gone
	 */
	@Tm1Endpoint("EntitySet MemberSets")
	public Mono<CellsetAxis> get(int ordinal, ODataQuery query) {
		return session.execute(Tm1Request.get(MEMBER_SETS.key(ordinal), query, CellsetAxis.class));
	}

	/**
	 * {@code DELETE MemberSets(n)}: frees the set on the server.
	 * @param ordinal the set's ordinal
	 * @return completes when it's deleted
	 */
	@Tm1Endpoint("EntitySet MemberSets")
	public Mono<Void> delete(int ordinal) {
		return session.execute(Tm1Request.delete(MEMBER_SETS.key(ordinal)));
	}

}
