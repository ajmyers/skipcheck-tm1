package net.ajmyers.tm1.client.service.attribute;

import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.LocalizedAttributes;
import net.ajmyers.tm1.client.model.LocalizedAttributesPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Translated attribute values: an {@link AttributeOwner}'s {@code LocalizedAttributes},
 * one per locale, keyed by locale ID ({@code fr}, {@code de-CH}). The values are in
 * {@code Attributes}: {@code Caption}, and every other attribute in its
 * {@code dynamicProperties} (set with {@code AttributesPatch.Builder#property}).
 *
 * <p>
 * Only attributes the owner's kind has can be translated (see {@link AttributeService}):
 * {@code Caption} too, once it's defined. A member's translations are its element's, and
 * read only: write them through {@link AttributeOwner#element}.
 *
 * <p>
 * TM1 (11.8) creates a locale's translations once and reads them, and that's all: it
 * supports no PATCH or DELETE on them and refuses to create a locale twice. Changing or
 * removing translations goes through the <code>&#125;Localized…Attributes</code> control
 * cubes, as cell writes.
 *
 * <p>
 * Translate an element's attribute into French:
 * {@snippet class = examples.LocalizedAttributeServiceExamples region = translate}
 *
 * <p>
 * Read the translation back:
 * {@snippet class = examples.LocalizedAttributeServiceExamples region = read}
 */
public final class LocalizedAttributeService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public LocalizedAttributeService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET …/LocalizedAttributes}, streamed.
	 * @param owner whose translations
	 * @param query what to select, filter and order
	 * @return the owner's translations, one per locale
	 */
	@Tm1Endpoint("Navigation Dimension/LocalizedAttributes")
	@Tm1Endpoint("Navigation Hierarchy/LocalizedAttributes")
	@Tm1Endpoint("Navigation Element/LocalizedAttributes")
	@Tm1Endpoint("Navigation Member/LocalizedAttributes")
	@Tm1Endpoint("Navigation Subset/LocalizedAttributes")
	@Tm1Endpoint("Navigation Cube/LocalizedAttributes")
	@Tm1Endpoint("Navigation View/LocalizedAttributes")
	@Tm1Endpoint("Navigation Process/LocalizedAttributes")
	@Tm1Endpoint("Navigation Chore/LocalizedAttributes")
	public Flux<LocalizedAttributes> list(AttributeOwner owner, ODataQuery query) {
		return session.stream(Tm1Request.list(localized(owner), query, LocalizedAttributes.class));
	}

	/**
	 * {@code GET …/LocalizedAttributes('locale')}.
	 * @param owner whose translations
	 * @param locale the locale ID, such as {@code fr}
	 * @param query what to select
	 * @return the translations for the locale, or a {@code Tm1NotFoundException} if there
	 * are none
	 */
	@Tm1Endpoint("Navigation Dimension/LocalizedAttributes")
	@Tm1Endpoint("Navigation Hierarchy/LocalizedAttributes")
	@Tm1Endpoint("Navigation Element/LocalizedAttributes")
	@Tm1Endpoint("Navigation Member/LocalizedAttributes")
	@Tm1Endpoint("Navigation Subset/LocalizedAttributes")
	@Tm1Endpoint("Navigation Cube/LocalizedAttributes")
	@Tm1Endpoint("Navigation View/LocalizedAttributes")
	@Tm1Endpoint("Navigation Process/LocalizedAttributes")
	@Tm1Endpoint("Navigation Chore/LocalizedAttributes")
	public Mono<LocalizedAttributes> get(AttributeOwner owner, String locale, ODataQuery query) {
		return session.execute(Tm1Request.get(localized(owner, locale), query, LocalizedAttributes.class));
	}

	/**
	 * {@code POST …/LocalizedAttributes}: adds translations for a locale.
	 * @param owner whose translations
	 * @param translations the {@code LocaleID} and the translated {@code Attributes}
	 * @return the translations as created
	 */
	@Tm1Endpoint("Navigation Dimension/LocalizedAttributes")
	@Tm1Endpoint("Navigation Hierarchy/LocalizedAttributes")
	@Tm1Endpoint("Navigation Element/LocalizedAttributes")
	@Tm1Endpoint("Navigation Member/LocalizedAttributes")
	@Tm1Endpoint("Navigation Subset/LocalizedAttributes")
	@Tm1Endpoint("Navigation Cube/LocalizedAttributes")
	@Tm1Endpoint("Navigation View/LocalizedAttributes")
	@Tm1Endpoint("Navigation Process/LocalizedAttributes")
	@Tm1Endpoint("Navigation Chore/LocalizedAttributes")
	public Mono<LocalizedAttributes> create(AttributeOwner owner, LocalizedAttributesPatch translations) {
		return session.execute(Tm1Request.post(localized(owner), translations, LocalizedAttributes.class));
	}

	private static ODataPath localized(AttributeOwner owner) {
		return owner.path().nav("LocalizedAttributes");
	}

	private static ODataPath localized(AttributeOwner owner, String locale) {
		return localized(owner).key(Objects.requireNonNull(locale, "locale"));
	}

}
