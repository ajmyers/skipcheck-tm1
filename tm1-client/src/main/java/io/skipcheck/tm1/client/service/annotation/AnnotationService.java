package io.skipcheck.tm1.client.service.annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Annotation;
import io.skipcheck.tm1.client.model.AnnotationPatch;
import io.skipcheck.tm1.client.model.ApplicationContextFacet;
import io.skipcheck.tm1.client.model.ApplicationContextFacetValue;
import io.skipcheck.tm1.client.model.ApplicationContextFacetValuePatch;
import io.skipcheck.tm1.client.model.Attachment;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Annotations: comments on cube cells, as Planning Analytics Workspace makes them, and
 * the application context facets that tie them to cubes.
 *
 * <p>
 * What TM1 (11.8) does here that you might not expect:
 * <ul>
 * <li>{@code GET Annotations} lists none, even with annotations made. They're found by
 * cube ({@link #cubeAnnotations}), by cell ({@link #cellAnnotations}), or by ID.</li>
 * <li>An annotation's {@code ApplicationContext} reads back empty, though the facet value
 * given when it was made exists ({@link #facetValues}).</li>
 * <li>Besides {@code Text}, an annotation carries properties {@code $metadata} doesn't
 * declare ({@code Annotation} is an open type): {@code objectName} (the cube),
 * {@code commentValue}, {@code commentType} and {@code commentLocation} (the cell's
 * elements, comma-separated). They're in {@code dynamicProperties}.</li>
 * <li>IDs look like <code>C&#125;ClientGroups~B.B~7uA_4TxLwG</code>.</li>
 * </ul>
 *
 * <p>
 * Comment on a cell:
 * {@snippet class = examples.AnnotationServiceExamples region = annotate}
 *
 * <p>
 * Read a cube's comments:
 * {@snippet class = examples.AnnotationServiceExamples region = read}
 *
 * <p>
 * Change what a comment says:
 * {@snippet class = examples.AnnotationServiceExamples region = edit}
 */
public final class AnnotationService {

	private static final ODataPath ANNOTATIONS = ODataPath.of("Annotations");

	private static final ODataPath FACETS = ODataPath.of("ApplicationContextFacets");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public AnnotationService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Annotations}, streamed. TM1 (11.8) lists none here: use
	 * {@link #cubeAnnotations} or {@link #cellAnnotations}.
	 * @param query what to select, expand and filter
	 * @return the annotations
	 */
	@Tm1Endpoint("EntitySet Annotations")
	public Flux<Annotation> list(ODataQuery query) {
		return session.stream(Tm1Request.list(ANNOTATIONS, query, Annotation.class));
	}

	/**
	 * {@code GET Annotations('id')}. TM1 (11.8) answers 404 only for an ID it once gave
	 * out, such as a deleted annotation's. Any other text is refused as a
	 * {@code Tm1BadRequestException} (400, code 248, "Invalid annotation id!").
	 * @param id the annotation's ID
	 * @param query what to select and expand
	 * @return the annotation; a {@code Tm1NotFoundException} if it's gone, or a
	 * {@code Tm1BadRequestException} for an ID that was never TM1's
	 */
	@Tm1Endpoint("EntitySet Annotations")
	public Mono<Annotation> get(String id, ODataQuery query) {
		return session.execute(Tm1Request.get(annotation(id), query, Annotation.class));
	}

	/**
	 * Annotates one cell, the way Planning Analytics Workspace does: {@code Text} and
	 * {@code commentValue} are the text, the {@code }Cubes} facet names the cube, and the
	 * dimensional context binds the cell's members.
	 * @param cube the cube
	 * @param elements the cell's element in each dimension's same-named hierarchy, by
	 * dimension, in the cube's dimension order
	 * @param text what the annotation says
	 * @return the annotation, with its ID
	 */
	@Tm1Endpoint("EntitySet Annotations")
	public Mono<Annotation> create(String cube, Map<String, String> elements, String text) {
		Objects.requireNonNull(cube, "cube");
		Objects.requireNonNull(text, "text");
		List<ODataPath> members = new ArrayList<>();
		elements.forEach((dimension,
				element) -> members.add(ODataPath.of("Dimensions")
					.key(dimension)
					.nav("Hierarchies")
					.key(dimension)
					.nav("Members")
					.key(element)));
		return create(AnnotationPatch.builder()
			.text(text)
			.applicationContext(List
				.of(ApplicationContextFacetValuePatch.builder().bindFacet(FACETS.key("}Cubes")).value(cube).build()))
			.bindDimensionalContext(members.toArray(ODataPath[]::new))
			.property("objectName", cube)
			.property("commentValue", text)
			.property("commentType", "ANNOTATION")
			.property("commentLocation", String.join(",", elements.values()))
			.build());
	}

	/**
	 * {@code POST Annotations}, for an annotation built by hand.
	 * @param annotation the annotation
	 * @return the annotation as made, with its ID
	 */
	@Tm1Endpoint("EntitySet Annotations")
	public Mono<Annotation> create(AnnotationPatch annotation) {
		return session.execute(Tm1Request.post(ANNOTATIONS, annotation, Annotation.class));
	}

	/**
	 * {@code PATCH Annotations('id')}. To change what it says, set both {@code Text} and
	 * the {@code commentValue} property: TM1 keeps them apart.
	 * @param id the annotation's ID
	 * @param patch what to change
	 * @return completes when it's changed; TM1 records {@code LastUpdatedBy} and
	 * {@code LastUpdated}
	 */
	@Tm1Endpoint("EntitySet Annotations")
	public Mono<Void> update(String id, AnnotationPatch patch) {
		return session.execute(Tm1Request.patch(annotation(id), patch));
	}

	/**
	 * {@code DELETE Annotations('id')}.
	 * @param id the annotation's ID
	 * @return completes when it's gone
	 */
	@Tm1Endpoint("EntitySet Annotations")
	public Mono<Void> delete(String id) {
		return session.execute(Tm1Request.delete(annotation(id)));
	}

	/**
	 * {@code ApplicationContext}, streamed. TM1 (11.8) returns none.
	 * @param id the annotation's ID
	 * @param query what to select and expand, such as {@code Facet}
	 * @return the facet values
	 */
	@Tm1Endpoint("Navigation Annotation/ApplicationContext")
	public Flux<ApplicationContextFacetValue> applicationContext(String id, ODataQuery query) {
		return session.stream(
				Tm1Request.list(annotation(id).nav("ApplicationContext"), query, ApplicationContextFacetValue.class));
	}

	/**
	 * {@code DimensionalContext}, streamed: the members of the annotated cell.
	 * @param id the annotation's ID
	 * @param query what to select
	 * @return the members
	 */
	@Tm1Endpoint("Navigation Annotation/DimensionalContext")
	public Flux<Member> dimensionalContext(String id, ODataQuery query) {
		return session.stream(Tm1Request.list(annotation(id).nav("DimensionalContext"), query, Member.class));
	}

	/**
	 * {@code Attachments}, streamed: documents attached to the annotation.
	 * @param id the annotation's ID
	 * @param query what to select
	 * @return the attachments
	 */
	@Tm1Endpoint("Navigation Annotation/Attachments")
	public Flux<Attachment> attachments(String id, ODataQuery query) {
		return session.stream(Tm1Request.list(annotation(id).nav("Attachments"), query, Attachment.class));
	}

	/**
	 * {@code Cubes('c')/Annotations}, streamed: a cube's annotations.
	 * @param cube the cube
	 * @param query what to select, expand and filter
	 * @return the annotations
	 */
	@Tm1Endpoint("Navigation Cube/Annotations")
	public Flux<Annotation> cubeAnnotations(String cube, ODataQuery query) {
		return session
			.stream(Tm1Request.list(ODataPath.of("Cubes").key(Objects.requireNonNull(cube, "cube")).nav("Annotations"),
					query, Annotation.class));
	}

	/**
	 * {@code Cellsets('id')/Cells(n)/Annotations}, streamed: one cellset cell's
	 * annotations. They also come with the cells by {@code expand("Annotations")}.
	 * @param cellset the cellset's ID
	 * @param ordinal the cell's ordinal
	 * @param query what to select
	 * @return the annotations
	 */
	@Tm1Endpoint("Navigation CellsetCell/Annotations")
	public Flux<Annotation> cellAnnotations(String cellset, long ordinal, ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.of("Cellsets")
			.key(Objects.requireNonNull(cellset, "cellset"))
			.nav("Cells")
			.key(ordinal)
			.nav("Annotations"), query, Annotation.class));
	}

	/**
	 * {@code GET ApplicationContextFacets}, streamed, such as {@code }Cubes}.
	 * @param query what to select and expand, such as {@code Values}
	 * @return the facets
	 */
	@Tm1Endpoint("EntitySet ApplicationContextFacets")
	public Flux<ApplicationContextFacet> facets(ODataQuery query) {
		return session.stream(Tm1Request.list(FACETS, query, ApplicationContextFacet.class));
	}

	/**
	 * A facet's {@code Values}, streamed: for {@code }Cubes}, the cubes with annotations.
	 * @param facet the facet's name
	 * @param query what to select
	 * @return the values
	 */
	@Tm1Endpoint("Navigation ApplicationContextFacet/Values")
	public Flux<ApplicationContextFacetValue> facetValues(String facet, ODataQuery query) {
		return session.stream(Tm1Request.list(FACETS.key(Objects.requireNonNull(facet, "facet")).nav("Values"), query,
				ApplicationContextFacetValue.class));
	}

	/**
	 * A facet value's {@code Facet}.
	 * @param facet the facet's name
	 * @param value the value
	 * @param query what to select
	 * @return the facet
	 */
	@Tm1Endpoint("Navigation ApplicationContextFacetValue/Facet")
	public Mono<ApplicationContextFacet> facet(String facet, String value, ODataQuery query) {
		return session.execute(Tm1Request.get(FACETS.key(Objects.requireNonNull(facet, "facet"))
			.nav("Values")
			.key(Objects.requireNonNull(value, "value"))
			.nav("Facet"), query, ApplicationContextFacet.class));
	}

	/**
	 * {@code DeleteAnnotationArtifacts}: removes every annotation in the model, with
	 * their facet values, attachments and control cubes. It can't be undone.
	 * @return completes when they're gone
	 */
	@Tm1Endpoint("ActionImport DeleteAnnotationArtifacts")
	public Mono<Void> deleteAnnotationArtifacts() {
		Tm1Operation operation = Tm1Operations.DELETE_ANNOTATION_ARTIFACTS;
		return session
			.execute(Tm1Request.post(ODataPath.of(Objects.requireNonNull(operation.importName())), null, Void.class)
				.requires(operation.capability()));
	}

	private static ODataPath annotation(String id) {
		return ANNOTATIONS.key(Objects.requireNonNull(id, "id"));
	}

}
