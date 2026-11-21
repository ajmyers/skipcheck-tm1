package net.ajmyers.tm1.client.odata;

import java.util.List;
import java.util.Map;

/** OData 4.0 JSON control information names, and helpers for writing them. */
public final class ODataJson {

	/** The context URL. */
	public static final String CONTEXT = "@odata.context";
	/** An entity's ETag, for optimistic concurrency ({@code If-Match}). */
	public static final String ETAG = "@odata.etag";
	/** The (derived) type of an entity, e.g. {@code #ibm.tm1.api.v1.MDXView}. */
	public static final String TYPE = "@odata.type";
	/** An entity's ID. */
	public static final String ID = "@odata.id";
	/** The next page of a partial collection. */
	public static final String NEXT_LINK = "@odata.nextLink";
	/** The link for changes since this response, with change tracking. */
	public static final String DELTA_LINK = "@odata.deltaLink";
	/** The total number of items, with {@code $count=true}. */
	public static final String COUNT = "@odata.count";
	/** The suffix binding a navigation property to existing entities in a request. */
	public static final String BIND_SUFFIX = "@odata.bind";

	private ODataJson() {
	}

	/**
	 * A single-valued binding, for a request body:
	 * {@code "Subset@odata.bind": "Dimensions('d')/Hierarchies('h')/Subsets('s')"}.
	 */
	public static Map<String, Object> bind(String navigation, ODataPath target) {
		return Map.of(navigation + BIND_SUFFIX, target.toString());
	}

	/**
	 * A collection-valued binding: {@code "Dimensions@odata.bind": ["Dimensions('a')", ...]}. In
	 * OData 4.0, which TM1 speaks, this is the only way to link to existing entities.
	 */
	public static Map<String, Object> bind(String navigation, List<ODataPath> targets) {
		return Map.of(navigation + BIND_SUFFIX, targets.stream().map(ODataPath::toString).toList());
	}
}
