// SPDX-License-Identifier: Apache-2.0

import { Cube, NativeView, View, type ViewCreate, type ViewPatch } from "../../generated/index.js";
import { ODataPath, type ODataQuery } from "../../odata/index.js";
import { Tm1Request } from "../../request.js";
import type { Tm1CallOptions } from "../../session/session.js";
import type { Tm1Stream } from "../../stream.js";
import { required } from "../../util.js";
import { Tm1Service, type Tm1Endpoints } from "../service.js";
import { ViewScope } from "./ViewScope.js";

const VIEWS = ["Navigation Cube/Views", "Navigation Cube/PrivateViews"] as const;

/** Views: a cube's public and private views ({@link ViewScope}), native (`NativeView`: subsets on
 * columns, rows and titles) or MDX (`MDXView`). They're read as `View`, keeping `@odata.type`, so
 * `typeOf(View, v)` tells the kinds apart.
 *
 * A native view's subsets are in complex properties, reached by expanding through the
 * `tm1.NativeView` type: {@link list} with `tm1.NativeView/Columns/Subset`, or {@link getNative},
 * which reads one view through the type. On TM1 11.8.35 the type-cast expand works on a list
 * holding MDX views too; an older server was reported to fail it, and `getNative` doesn't depend on
 * it.
 *
 * Create a native view: each axis binds a subset, and every dimension of the cube has to be on one:
 * ```ts
 * const subset = (d: string) => ODataPath.of("Dimensions").key(d).nav("Hierarchies").key(d).nav("Subsets").key("All");
 * await tm1.views.create("Sales", ViewScope.Public, NativeViewPatch.builder()
 *     .name("Default")
 *     .columns([ViewAxisSelectionPatch.builder().bindSubset(subset("Month")).build()])
 *     .rows([ViewAxisSelectionPatch.builder().bindSubset(subset("Region")).build()])
 *     .build());
 * ```
 * Create an MDX view from its query:
 * ```ts
 * await tm1.views.create("Sales", ViewScope.Public, MDXViewCreate.builder().name("Mdx").mdx("SELECT … FROM [Sales]").build());
 * ```
 * Read the subsets on a native view's axes:
 * ```ts
 * const view = await tm1.views.getNative("Sales", ViewScope.Public, "Default", ODataQuery.none().expand("Columns/Subset").expand("Rows/Subset"));
 * ```
 */
export class ViewService extends Tm1Service {
  /** The endpoints each method implements, by method name, as coverage-gate IDs: the Java client's
   * `@Tm1Endpoint`. */
  static readonly endpoints = {
    list: VIEWS,
    get: VIEWS,
    getNative: [...VIEWS, "Navigation ViewAxisSelection/Subset", "Navigation ViewTitle/Subset", "Navigation ViewTitle/Selected"],
    create: VIEWS,
    update: VIEWS,
    delete: VIEWS,
    cube: ["Navigation View/Cube"],
  } as const satisfies Tm1Endpoints<ViewService>;

  /** `GET Cubes('c')/Views` (or the private ones), streamed; a native view's axes expand through
   * its type, as `tm1.NativeView/Columns/Subset`. */
  list(cube: string, scope: ViewScope, query?: ODataQuery | null, options?: Tm1CallOptions): Tm1Stream<View> {
    return this.session.stream(Tm1Request.list(views(cube, scope), query, View), options);
  }

  /** `GET Cubes('c')/Views('v')`: the view as its own type, or a `Tm1NotFoundException` if there's none. */
  get(cube: string, scope: ViewScope, view: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<View> {
    return this.session.execute(Tm1Request.get(viewPath(cube, scope, view), query, View), options);
  }

  /** `GET Cubes('c')/Views('v')/ibm.tm1.api.v1.NativeView`: reads a native view through its type,
   * so its axes can be expanded: `Columns/Subset`, `Rows/Subset`, `Titles/Subset`,
   * `Titles/Selected`. They're complex properties, so TM1 wants paths, not nested options. An MDX
   * view is an error. */
  getNative(cube: string, scope: ViewScope, view: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<NativeView> {
    return this.session.execute(Tm1Request.get(viewPath(cube, scope, view).cast("ibm.tm1.api.v1.NativeView"), query, NativeView), options);
  }

  /** `POST Cubes('c')/Views`: creates a view; the patch's type says which kind (`MDXViewPatch`,
   * `NativeViewPatch`), and a native view's axes bind subsets. TM1 (11.8) refuses a native view that
   * leaves any of the cube's dimensions off its columns, rows and titles (400, "All cube dimensions
   * must be placed on an axis"). Returns the view as created. */
  create(cube: string, scope: ViewScope, view: ViewCreate, options?: Tm1CallOptions): Promise<View> {
    return this.session.execute(Tm1Request.post(views(cube, scope), required(view, "view"), View), options);
  }

  /** `PATCH Cubes('c')/Views('v')`: changes the properties the patch sets, such as an MDX view's
   * `MDX`. With `etag` (from a read), sent as `If-Match`: only if the view hasn't changed since,
   * else a `Tm1PreconditionFailedException`. */
  async update(cube: string, scope: ViewScope, view: string, patch: ViewPatch, etag?: string | null, options?: Tm1CallOptions): Promise<void> {
    const request = Tm1Request.patch(viewPath(cube, scope, view), required(patch, "patch"));
    await this.session.execute(etag == null ? request : request.ifMatch(etag), options);
  }

  /** `DELETE Cubes('c')/Views('v')`; its subsets stay. */
  delete(cube: string, scope: ViewScope, view: string, options?: Tm1CallOptions): Promise<void> {
    return this.session.execute(Tm1Request.delete(viewPath(cube, scope, view)), options);
  }

  /** `GET Cubes('c')/Views('v')/Cube`: the view's cube. */
  cube(cube: string, scope: ViewScope, view: string, query?: ODataQuery | null, options?: Tm1CallOptions): Promise<Cube> {
    return this.session.execute(Tm1Request.get(viewPath(cube, scope, view).nav("Cube"), query, Cube), options);
  }
}

/** `Cubes('c')/Views` or `Cubes('c')/PrivateViews`. */
function views(cube: string, scope: ViewScope): ODataPath {
  return ODataPath.of("Cubes")
    .key(required(cube, "cube"))
    .nav(ViewScope.navigation(required(scope, "scope")));
}

function viewPath(cube: string, scope: ViewScope, view: string): ODataPath {
  return views(cube, scope).key(required(view, "view"));
}
