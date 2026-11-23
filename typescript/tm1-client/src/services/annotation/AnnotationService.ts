import {
  Annotation,
  AnnotationPatch,
  ApplicationContextFacet,
  ApplicationContextFacetValue,
  ApplicationContextFacetValuePatch,
  Attachment,
  Member,
  Tm1Operations,
} from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { entriesOf, required, type Tm1Map } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";

const ANNOTATIONS = ODataPath.of("Annotations");
const FACETS = ODataPath.of("ApplicationContextFacets");

/** Annotations: comments on cube cells, as Planning Analytics Workspace makes them, and the
 * application context facets that tie them to cubes.
 *
 * What TM1 (11.8) does here that you might not expect:
 * - `GET Annotations` lists none, even with annotations made. They're found by cube
 *   ({@link cubeAnnotations}), by cell ({@link cellAnnotations}), or by ID.
 * - An annotation's `ApplicationContext` reads back empty, though the facet value given when it was
 *   made exists ({@link facetValues}).
 * - Besides `Text`, an annotation carries properties `$metadata` doesn't declare (`Annotation` is
 *   an open type): `objectName` (the cube), `commentValue`, `commentType` and `commentLocation`
 *   (the cell's elements, comma-separated). They're properties of the annotation as read.
 * - IDs look like `C}ClientGroups~B.B~7uA_4TxLwG`.
 *
 * Comment on a cell:
 * ```ts
 * const note = await tm1.annotations.create("Sales", new Map([["Region", "North"], ["Month", "Jan"]]), "Check this");
 * ```
 * Read a cube's comments:
 * ```ts
 * for await (const a of tm1.annotations.cubeAnnotations("Sales")) console.log(a.commentLocation, a.Text);
 * ```
 * Change what a comment says:
 * ```ts
 * await tm1.annotations.update(note.ID!, AnnotationPatch.builder().text("Checked").property("commentValue", "Checked").build());
 * ```
 */
export class AnnotationService extends Tm1Service {
  static readonly endpoints = {
    list: ["EntitySet Annotations"],
    get: ["EntitySet Annotations"],
    create: ["EntitySet Annotations"],
    update: ["EntitySet Annotations"],
    delete: ["EntitySet Annotations"],
    applicationContext: ["Navigation Annotation/ApplicationContext"],
    dimensionalContext: ["Navigation Annotation/DimensionalContext"],
    attachments: ["Navigation Annotation/Attachments"],
    cubeAnnotations: ["Navigation Cube/Annotations"],
    cellAnnotations: ["Navigation CellsetCell/Annotations"],
    facets: ["EntitySet ApplicationContextFacets"],
    facetValues: ["Navigation ApplicationContextFacet/Values"],
    facet: ["Navigation ApplicationContextFacetValue/Facet"],
    deleteAnnotationArtifacts: ["ActionImport DeleteAnnotationArtifacts"],
  } as const satisfies Tm1Endpoints<AnnotationService>;

  /** `GET Annotations`, streamed. TM1 (11.8) lists none here: use {@link cubeAnnotations} or
   * {@link cellAnnotations}. */
  list(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Annotation> {
    return this.session.stream(Tm1Request.list(ANNOTATIONS, query, Annotation), options);
  }

  /** `GET Annotations('id')`: the annotation, or a `Tm1NotFoundException` if there's none. */
  get(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Annotation> {
    return this.session.execute(Tm1Request.get(annotationPath(id), query, Annotation), options);
  }

  /** Annotates one cell, the way Planning Analytics Workspace does: `Text` and `commentValue` are
   * the text, the `}Cubes` facet names the cube, and the dimensional context binds the cell's
   * members. `elements` is the cell's element in each dimension's same-named hierarchy, by
   * dimension, in the cube's dimension order (a `Map` keeps it whatever the names). Returns the
   * annotation, with its ID. */
  create(cube: string, elements: Tm1Map<string>, text: string, options?: Tm1CallOptions): Promise<Annotation>;
  /** `POST Annotations`, for an annotation built by hand; returns it as made, with its ID. */
  create(annotation: AnnotationPatch, options?: Tm1CallOptions): Promise<Annotation>;
  create(first: string | AnnotationPatch, second?: Tm1Map<string> | Tm1CallOptions, text?: string, options?: Tm1CallOptions): Promise<Annotation> {
    if (typeof first !== "string") {
      return this.session.execute(Tm1Request.post(ANNOTATIONS, required(first, "annotation"), Annotation), second as Tm1CallOptions | undefined);
    }
    const cube = first;
    required(text, "text");
    const cell = entriesOf(required(second as Tm1Map<string>, "elements"));
    const dimensions = ODataPath.of("Dimensions");
    const members = cell.map(([dimension, element]) => dimensions.key(dimension).nav("Hierarchies").key(dimension).nav("Members").key(element));
    const annotation = AnnotationPatch.builder()
      .text(text!)
      .applicationContext([ApplicationContextFacetValuePatch.builder().bindFacet(FACETS.key("}Cubes")).value(cube).build()])
      .bindDimensionalContext(...members)
      .property("objectName", cube)
      .property("commentValue", text)
      .property("commentType", "ANNOTATION")
      .property("commentLocation", cell.map(([, element]) => element).join(","))
      .build();
    return this.create(annotation, options);
  }

  /** `PATCH Annotations('id')`. To change what it says, set both `Text` and the `commentValue`
   * property: TM1 keeps them apart. TM1 records `LastUpdatedBy` and `LastUpdated`. */
  update(id: string, patch: AnnotationPatch, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.patch(annotationPath(id), required(patch, "patch")), options);
  }

  /** `DELETE Annotations('id')`. */
  delete(id: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(annotationPath(id)), options);
  }

  /** `ApplicationContext`, streamed: the facet values (expand `Facet` with the query). TM1 (11.8)
   * returns none. */
  applicationContext(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ApplicationContextFacetValue> {
    return this.session.stream(Tm1Request.list(annotationPath(id).nav("ApplicationContext"), query, ApplicationContextFacetValue), options);
  }

  /** `DimensionalContext`, streamed: the members of the annotated cell. */
  dimensionalContext(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Member> {
    return this.session.stream(Tm1Request.list(annotationPath(id).nav("DimensionalContext"), query, Member), options);
  }

  /** `Attachments`, streamed: documents attached to the annotation. */
  attachments(id: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Attachment> {
    return this.session.stream(Tm1Request.list(annotationPath(id).nav("Attachments"), query, Attachment), options);
  }

  /** `Cubes('c')/Annotations`, streamed: a cube's annotations. */
  cubeAnnotations(cube: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Annotation> {
    return this.session.stream(Tm1Request.list(ODataPath.of("Cubes").key(required(cube, "cube")).nav("Annotations"), query, Annotation), options);
  }

  /** `Cellsets('id')/Cells(n)/Annotations`, streamed: one cellset cell's annotations. They also come
   * with the cells by `expand("Annotations")`. */
  cellAnnotations(cellset: string, ordinal: number, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<Annotation> {
    const path = ODataPath.of("Cellsets").key(required(cellset, "cellset")).nav("Cells").key(required(ordinal, "ordinal")).nav("Annotations");
    return this.session.stream(Tm1Request.list(path, query, Annotation), options);
  }

  /** `GET ApplicationContextFacets`, streamed, such as `}Cubes` (expand `Values` with the query). */
  facets(query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ApplicationContextFacet> {
    return this.session.stream(Tm1Request.list(FACETS, query, ApplicationContextFacet), options);
  }

  /** A facet's `Values`, streamed: for `}Cubes`, the cubes with annotations. */
  facetValues(facet: string, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<ApplicationContextFacetValue> {
    return this.session.stream(Tm1Request.list(FACETS.key(required(facet, "facet")).nav("Values"), query, ApplicationContextFacetValue), options);
  }

  /** A facet value's `Facet`. */
  facet(facet: string, value: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<ApplicationContextFacet> {
    const path = FACETS.key(required(facet, "facet")).nav("Values").key(required(value, "value")).nav("Facet");
    return this.session.execute(Tm1Request.get(path, query, ApplicationContextFacet), options);
  }

  /** `DeleteAnnotationArtifacts`: removes every annotation in the model, with their facet values,
   * attachments and control cubes. It can't be undone. */
  deleteAnnotationArtifacts(options?: Tm1CallOptions): Promise<void> {
    const operation = Tm1Operations.DeleteAnnotationArtifacts;
    return this.session.execute(Tm1Request.post(ODataPath.of(required(operation.importName, "importName"))).requires(operation.capability()), options);
  }
}

function annotationPath(id: string): ODataPath {
  return ANNOTATIONS.key(required(id, "id"));
}
