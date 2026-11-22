package net.ajmyers.tm1.client.service.cube;

import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Cube;
import net.ajmyers.tm1.client.model.NativeView;
import net.ajmyers.tm1.client.model.View;
import net.ajmyers.tm1.client.model.ViewPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Views: a cube's public and private views ({@link ViewScope}), native
 * ({@code NativeView}: subsets on columns, rows and titles) or MDX ({@code MDXView}).
 * They're read as the sealed {@link View}, so each comes back as its own type.
 *
 * <p>
 * A native view's subsets are in complex properties, reached by expanding through the
 * {@code tm1.NativeView} type: {@code list} with {@code tm1.NativeView/Columns/Subset},
 * or {@link #getNative}, which reads one view through the type. On TM1 11.8.35 the
 * type-cast expand works on a list holding MDX views too; an older server was reported to
 * fail it (plan §16.5), and {@code getNative} doesn't depend on it.
 */
public final class ViewService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ViewService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Cubes('c')/Views} (or the private ones), streamed.
	 * @param cube the cube's name
	 * @param scope which views
	 * @param query what to select, expand, filter and order; a native view's axes expand
	 * through its type, as {@code tm1.NativeView/Columns/Subset}
	 * @return the views, each as its own type
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	public Flux<View> list(String cube, ViewScope scope, ODataQuery query) {
		return session.stream(Tm1Request.list(views(cube, scope), query, View.class));
	}

	/**
	 * {@code GET Cubes('c')/Views('v')}.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @param query what to select and expand
	 * @return the view as its own type, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	public Mono<View> get(String cube, ViewScope scope, String view, ODataQuery query) {
		return session.execute(Tm1Request.get(view(cube, scope, view), query, View.class));
	}

	/**
	 * {@code GET Cubes('c')/Views('v')/tm1.NativeView}: reads a native view through its
	 * type, so its axes can be expanded: {@code Columns/Subset}, {@code Rows/Subset},
	 * {@code Titles/Subset}, {@code Titles/Selected}. They're complex properties, so TM1
	 * wants paths, not nested options.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @param query what to select and expand
	 * @return the native view, or an error if it's an MDX view
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	@Tm1Endpoint("Navigation ViewAxisSelection/Subset")
	@Tm1Endpoint("Navigation ViewTitle/Subset")
	@Tm1Endpoint("Navigation ViewTitle/Selected")
	public Mono<NativeView> getNative(String cube, ViewScope scope, String view, ODataQuery query) {
		return session.execute(
				Tm1Request.get(view(cube, scope, view).cast("ibm.tm1.api.v1.NativeView"), query, NativeView.class));
	}

	/**
	 * {@code POST Cubes('c')/Views}: creates a view; the patch's type says which kind
	 * ({@code MDXViewPatch}, {@code NativeViewPatch}), and a native view's axes bind
	 * subsets.
	 * @param cube the cube's name
	 * @param scope where to create it
	 * @param view the new view; it needs a name
	 * @return the view as created
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	public Mono<View> create(String cube, ViewScope scope, ViewPatch view) {
		return session.execute(Tm1Request.post(views(cube, scope), view, View.class));
	}

	/**
	 * {@code PATCH Cubes('c')/Views('v')}: changes the properties the patch sets, such as
	 * an MDX view's {@code MDX}.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @param patch the changes, of the view's kind
	 * @return completes when the view is changed
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	public Mono<Void> update(String cube, ViewScope scope, String view, ViewPatch patch) {
		return session.execute(Tm1Request.patch(view(cube, scope, view), patch));
	}

	/**
	 * {@code PATCH Cubes('c')/Views('v')} with {@code If-Match}.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @param patch the changes, of the view's kind
	 * @param etag the ETag the view was read with
	 * @return completes when the view is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	public Mono<Void> update(String cube, ViewScope scope, String view, ViewPatch patch, String etag) {
		return session.execute(Tm1Request.patch(view(cube, scope, view), patch).ifMatch(etag));
	}

	/**
	 * {@code DELETE Cubes('c')/Views('v')}; its subsets stay.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @return completes when the view is deleted
	 */
	@Tm1Endpoint("Navigation Cube/Views")
	@Tm1Endpoint("Navigation Cube/PrivateViews")
	public Mono<Void> delete(String cube, ViewScope scope, String view) {
		return session.execute(Tm1Request.delete(view(cube, scope, view)));
	}

	/**
	 * {@code GET Cubes('c')/Views('v')/Cube}.
	 * @param cube the cube's name
	 * @param scope where the view is
	 * @param view the view's name
	 * @param query what to select and expand
	 * @return the view's cube
	 */
	@Tm1Endpoint("Navigation View/Cube")
	public Mono<Cube> cube(String cube, ViewScope scope, String view, ODataQuery query) {
		return session.execute(Tm1Request.get(view(cube, scope, view).nav("Cube"), query, Cube.class));
	}

	private static ODataPath views(String cube, ViewScope scope) {
		return ODataPath.of("Cubes").key(Objects.requireNonNull(cube, "cube")).nav(scope.navigation());
	}

	private static ODataPath view(String cube, ViewScope scope, String view) {
		return views(cube, scope).key(Objects.requireNonNull(view, "view"));
	}

}
