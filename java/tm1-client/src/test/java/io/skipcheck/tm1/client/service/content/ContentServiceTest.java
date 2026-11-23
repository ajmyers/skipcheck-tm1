// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceCodec;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1ConflictException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.CubeReference;
import io.skipcheck.tm1.client.model.CubeReferencePatch;
import io.skipcheck.tm1.client.model.Document;
import io.skipcheck.tm1.client.model.DocumentPatch;
import io.skipcheck.tm1.client.model.DocumentReference;
import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.model.Folder;
import io.skipcheck.tm1.client.model.FolderPatch;
import io.skipcheck.tm1.client.model.Link;
import io.skipcheck.tm1.client.model.LinkPatch;
import io.skipcheck.tm1.client.model.LocalizedAttributes;
import io.skipcheck.tm1.client.odata.Filter;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ContentService against a fake TM1: what each method sends, and how it reads TM1's
 * answer. Every call is recorded to
 * {@code spec/conformance/calls/ContentServiceTest.json}, which the Python, .NET and
 * TypeScript clients replay.
 */
@SuppressWarnings("deprecation") // Entry.ID is deprecated in $metadata, yet it's TM1's
									// key
class ContentServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	static {
		ConformanceCodec.register(ContentPath.class, ContentServiceTest::encode);
	}

	private static final ContentPath PLANNING = ContentPath.applications().child("Planning");

	private static final ContentPath NOTES = PLANNING.child("notes.txt.blob");

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	/**
	 * A path as the factories that make it: {@code applications()}, {@code blobs()} or
	 * {@code root(id)}, then {@code child(id, scope)} on it for each step.
	 */
	static JsonNode encode(Object value) {
		try {
			Field field = ContentPath.class.getDeclaredField("steps");
			field.setAccessible(true);
			JsonNode node = null;
			for (Object step : (List<?>) field.get(value)) {
				Method idOf = step.getClass().getDeclaredMethod("id");
				Method scopeOf = step.getClass().getDeclaredMethod("scope");
				idOf.setAccessible(true);
				scopeOf.setAccessible(true);
				String id = (String) idOf.invoke(step);
				if (node == null) {
					node = switch (id) {
						case "Applications" -> ConformanceCodec.factory("ContentPath", "applications");
						case "Blobs" -> ConformanceCodec.factory("ContentPath", "blobs");
						default -> ConformanceCodec.factory("ContentPath", "root", id);
					};
				}
				else {
					ObjectNode child = (ObjectNode) ConformanceCodec.factory("ContentPath", "child", id,
							scopeOf.invoke(step));
					((ArrayNode) child.get("args")).insert(0, node);
					node = child;
				}
			}
			return node;
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Flux<DataBuffer> bytes(String text) {
		return Flux.defer(
				() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8))));
	}

	// Paths: plain Java, nothing sent

	@Test
	void buildsPathsByIdInEachScope() {
		assertThat(ContentPath.applications().path()).hasToString("Contents('Applications')");
		assertThat(ContentPath.blobs().path()).hasToString("Contents('Blobs')");
		assertThat(ContentPath.root("Other").path()).hasToString("Contents('Other')");
		ContentPath mine = PLANNING.child("mine", ContentScope.PRIVATE).child("x.blob");
		assertThat(mine.path())
			.hasToString("Contents('Applications')/Contents('Planning')/PrivateContents('mine')/Contents('x.blob')");
		assertThat(mine).hasToString("Applications/Planning/(private) mine/x.blob");
		assertThat(PLANNING.child("Joe's").path())
			.hasToString("Contents('Applications')/Contents('Planning')/Contents('Joe''s')");
		assertThat(NOTES.inBlobs()).isFalse();
		assertThat(ContentPath.blobs().child("a.txt").inBlobs()).isTrue();
		assertThat(PLANNING.child("x"))
			.isEqualTo(ContentPath.root("Applications").child("Planning").child("x", ContentScope.PUBLIC))
			.hasSameHashCodeAs(ContentPath.root("Applications").child("Planning").child("x", ContentScope.PUBLIC))
			.isNotEqualTo(PLANNING.child("x", ContentScope.PRIVATE));
		assertThat(ContentScope.PUBLIC.navigation()).isEqualTo("Contents");
		assertThat(ContentScope.PRIVATE.navigation()).isEqualTo("PrivateContents");
	}

	@Test
	void findsAChildByTheIdOfTheEntryTmOneReturned() {
		Entry made = new DocumentReference(null, "notes.txt.blob", "notes.txt", null, null, null, null);
		assertThat(PLANNING.child(made)).isEqualTo(NOTES);
		assertThat(PLANNING.child(made, ContentScope.PRIVATE))
			.isEqualTo(PLANNING.child("notes.txt.blob", ContentScope.PRIVATE));
		Entry selectedAway = new DocumentReference(null, null, "notes.txt", null, null, null, null);
		assertThatThrownBy(() -> PLANNING.child(selectedAway)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PLANNING.child((String) null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> PLANNING.child("x", null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> ContentPath.root(null)).isInstanceOf(NullPointerException.class);
	}

	// Recorded calls

	@Test
	void listsTheRoots() {
		tm1().json(
				"{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"Applications\",\"Name\":\"Applications\"},"
						+ "{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"Blobs\",\"Name\":\"Blobs\"}]}");
		List<Entry> roots = conformance.call("contents", "roots", ODataQuery.none());
		assertThat(roots).extracting(Entry::name).containsExactly("Applications", "Blobs");
		assertThat(roots).allMatch(Folder.class::isInstance);
		assertThat(tm1().last().key()).isEqualTo("GET Contents");

		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"Name\":\"Blobs\"}]}");
		conformance.call("contents", "roots",
				ODataQuery.none().select("Name").filter(Filter.raw("Name eq 'Blobs'")).orderBy("Name"));
		assertThat(tm1().last().target()).isEqualTo("Contents?$select=Name&$filter=Name%20eq%20'Blobs'&$orderby=Name");
	}

	@Test
	void getsAnEntryOfItsOwnKind() {
		tm1().json(
				"{\"@odata.type\":\"#ibm.tm1.api.v1.Link\",\"ID\":\"ibm.extr\",\"Name\":\"ibm\",\"URL\":\"https://www.ibm.com\"}");
		Entry link = conformance.call("contents", "get", PLANNING.child("ibm.extr"), ODataQuery.none());
		assertThat(link).isInstanceOfSatisfying(Link.class, l -> assertThat(l.url()).isEqualTo("https://www.ibm.com"));
		assertThat(tm1().last().key())
			.isEqualTo("GET Contents('Applications')/Contents('Planning')/Contents('ibm.extr')");

		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.CubeReference\",\"ID\":\"}ClientGroups.cube\","
				+ "\"Name\":\"}ClientGroups\",\"Cube\":{\"Name\":\"}ClientGroups\"}}");
		Entry reference = conformance.call("contents", "get", PLANNING.child("}ClientGroups.cube"),
				ODataQuery.none().select("ID", "Name").expand("tm1.CubeReference/Cube", q -> q.select("Name")));
		assertThat(reference).isInstanceOfSatisfying(CubeReference.class,
				r -> assertThat(r.cube().name()).isEqualTo("}ClientGroups"));
		assertThat(tm1().last().target()).isEqualTo(
				"Contents('Applications')/Contents('Planning')/Contents('%7DClientGroups.cube')?$select=ID,Name&$expand=tm1.CubeReference/Cube($select%3DName)");

		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"Applications\",\"Name\":\"Applications\"}");
		Entry root = conformance.call("contents", "get", ContentPath.applications(), ODataQuery.none());
		assertThat(root).isInstanceOf(Folder.class);
		assertThat(tm1().last().target()).isEqualTo("Contents('Applications')");
	}

	@Test
	void getsAPrivateEntry() {
		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"mine\",\"Name\":\"mine\"}");
		conformance.call("contents", "get", PLANNING.child("mine", ContentScope.PRIVATE), ODataQuery.none());
		assertThat(tm1().last().target())
			.isEqualTo("Contents('Applications')/Contents('Planning')/PrivateContents('mine')");
	}

	@Test
	void quotesAndEncodesIds() {
		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.DocumentReference\",\"ID\":\"Joe's plan #1 (50%).txt.blob\"}");
		conformance.call("contents", "get", PLANNING.child("Joe's plan #1 (50%).txt.blob"), ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo(
				"Contents('Applications')/Contents('Planning')/Contents('Joe''s%20plan%20%231%20%2850%25%29.txt.blob')");

		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"a/b?c&d\"}");
		conformance.call("contents", "get", ContentPath.root("Applications").child("a/b?c&d"), ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Contents('Applications')/Contents('a%2Fb%3Fc%26d')");
	}

	@Test
	void aMissingEntryIsNotFound() {
		tm1().error(404, "278", "'notes.txt' can not be found in collection of type 'Entry'.");
		Throwable error = conformance.fail("contents", "get", PLANNING.child("notes.txt"), ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void listsAFoldersPublicAndPrivateEntries() {
		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.Link\",\"ID\":\"ibm.extr\",\"Name\":\"ibm\"},"
				+ "{\"@odata.type\":\"#ibm.tm1.api.v1.CubeReference\",\"ID\":\"}ClientGroups.cube\",\"Name\":\"}ClientGroups\","
				+ "\"Cube\":{\"Name\":\"}ClientGroups\"}}]}");
		List<Entry> entries = conformance.call("contents", "contents", PLANNING, ContentScope.PUBLIC,
				ODataQuery.none().expand("tm1.CubeReference/Cube", q -> q.select("Name")));
		assertThat(entries).hasSize(2);
		assertThat(entries.get(1)).isInstanceOf(CubeReference.class);
		assertThat(tm1().last().target()).isEqualTo(
				"Contents('Applications')/Contents('Planning')/Contents?$expand=tm1.CubeReference/Cube($select%3DName)");

		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"mine\",\"Name\":\"mine\"}],"
				+ "\"@odata.nextLink\":\"Contents('Applications')/Contents('Planning')/PrivateContents?$skiptoken=1\"}");
		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"also\",\"Name\":\"also\"}]}");
		List<Entry> mine = conformance.call("contents", "contents", PLANNING, ContentScope.PRIVATE,
				ODataQuery.none().top(1));
		assertThat(mine).extracting(Entry::id).containsExactly("mine", "also");
		assertThat(tm1().requests()).extracting(ConformanceTm1.Recorded::target)
			.endsWith("Contents('Applications')/Contents('Planning')/PrivateContents?$top=1",
					"Contents('Applications')/Contents('Planning')/PrivateContents?$skiptoken=1");
	}

	@Test
	void aPathIsRequired() {
		assertThat(conformance.fail("contents", "contents", null, ContentScope.PUBLIC, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("contents", "get", null, ODataQuery.none()))
			.isInstanceOf(NullPointerException.class);
		assertThat(conformance.fail("contents", "copy", NOTES, null, ContentScope.PUBLIC, null))
			.isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void createsFoldersDocumentsLinksAndReferences() {
		tm1().json(201, "{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"Planning\",\"Name\":\"Planning\"}");
		Entry folder = conformance.call("contents", "create", ContentPath.applications(), ContentScope.PUBLIC,
				FolderPatch.builder().name("Planning").build());
		assertThat(folder).isInstanceOf(Folder.class);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST Contents('Applications')/Contents");
		assertThat(post.header("Content-Type")).isEqualTo("application/json");

		tm1().json(201,
				"{\"@odata.type\":\"#ibm.tm1.api.v1.DocumentReference\",\"ID\":\"notes.txt.blob\",\"Name\":\"notes.txt\"}");
		Entry document = conformance.call("contents", "create", PLANNING, ContentScope.PUBLIC,
				DocumentPatch.builder().name("notes.txt").build());
		assertThat(document).as("a document made in an application is a reference")
			.isInstanceOf(DocumentReference.class);
		assertThat(document.id()).isEqualTo("notes.txt.blob");
		assertThat(PLANNING.child(document)).isEqualTo(NOTES);

		tm1().json(201,
				"{\"@odata.type\":\"#ibm.tm1.api.v1.Link\",\"ID\":\"ibm.extr\",\"Name\":\"ibm\",\"URL\":\"https://www.ibm.com\"}");
		conformance.call("contents", "create", PLANNING, ContentScope.PUBLIC,
				LinkPatch.builder().name("ibm").url("https://www.ibm.com").build());

		tm1().json(201,
				"{\"@odata.type\":\"#ibm.tm1.api.v1.CubeReference\",\"ID\":\"}ClientGroups.cube\",\"Name\":\"}ClientGroups\"}");
		conformance.call("contents", "create", PLANNING, ContentScope.PUBLIC,
				CubeReferencePatch.builder()
					.name("}ClientGroups")
					.bindCube(ODataPath.of("Cubes").key("}ClientGroups"))
					.build());
		assertThat(tm1().last().json().get("Cube@odata.bind").asString()).isEqualTo("Cubes('%7DClientGroups')");

		tm1().json(201, "{\"@odata.type\":\"#ibm.tm1.api.v1.Folder\",\"ID\":\"mine\",\"Name\":\"mine\"}");
		conformance.call("contents", "create", PLANNING, ContentScope.PRIVATE,
				FolderPatch.builder().name("mine").build());
		assertThat(tm1().last().key()).isEqualTo("POST Contents('Applications')/Contents('Planning')/PrivateContents");
	}

	@Test
	void aNameTakenIsAConflict() {
		tm1().error(409, "1", "An entry named 'Planning' already exists.");
		Throwable error = conformance.fail("contents", "create", ContentPath.applications(), ContentScope.PUBLIC,
				FolderPatch.builder().name("Planning").build());
		assertThat(error).isInstanceOf(Tm1ConflictException.class);
	}

	@Test
	void deletesAnEntry() {
		tm1().status(204);
		Object none = conformance.call("contents", "delete", PLANNING);
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("DELETE Contents('Applications')/Contents('Planning')");

		tm1().error(404, "278", "'gone' can not be found in collection of type 'Entry'.");
		assertThat(conformance.fail("contents", "delete", PLANNING.child("gone", ContentScope.PRIVATE)))
			.isInstanceOf(Tm1NotFoundException.class);
		assertThat(tm1().last().key())
			.isEqualTo("DELETE Contents('Applications')/Contents('Planning')/PrivateContents('gone')");
	}

	@Test
	void findsTheFolderAnEntryIsIn() {
		tm1().json("{\"ID\":\"Planning\",\"Name\":\"Planning\"}");
		Folder folder = conformance.call("contents", "containedBy", PLANNING.child("ibm.extr"),
				ODataQuery.none().select("ID"));
		assertThat(folder.id()).isEqualTo("Planning");
		assertThat(tm1().last().target())
			.isEqualTo("Contents('Applications')/Contents('Planning')/Contents('ibm.extr')/ContainedBy?$select=ID");
	}

	@Test
	void listsAnEntrysLocalizedAttributes() {
		tm1().json("{\"value\":[{\"LocaleID\":\"fr\",\"Attributes\":{\"Caption\":\"Planification\"}}]}");
		List<LocalizedAttributes> attributes = conformance.call("contents", "localizedAttributes", PLANNING,
				ODataQuery.none().filter(Filter.raw("LocaleID eq 'fr'")));
		assertThat(attributes).extracting(LocalizedAttributes::localeID).containsExactly("fr");
		assertThat(tm1().last().target()).isEqualTo(
				"Contents('Applications')/Contents('Planning')/LocalizedAttributes?$filter=LocaleID%20eq%20'fr'");

		tm1().json("{\"value\":[]}");
		List<LocalizedAttributes> none = conformance.call("contents", "localizedAttributes", NOTES, ODataQuery.none());
		assertThat(none).isEmpty();
	}

	@Test
	void copiesAnEntryNamedOrAsItIs() {
		ContentPath other = ContentPath.applications().child("Other");
		tm1().json(201,
				"{\"@odata.type\":\"#ibm.tm1.api.v1.DocumentReference\",\"ID\":\"copy.txt.blob\",\"Name\":\"copy.txt\"}");
		Entry copy = conformance.call("contents", "copy", NOTES, PLANNING, ContentScope.PUBLIC, "copy.txt");
		assertThat(copy.id()).isEqualTo("copy.txt.blob");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key())
			.isEqualTo("POST Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.Copy");
		assertThat(post.text()).isEqualTo(
				"{\"Target@odata.bind\":\"Contents('Applications')/Contents('Planning')\",\"Private\":false,\"Name\":\"copy.txt\"}");

		tm1().json(201, "{\"@odata.type\":\"#ibm.tm1.api.v1.DocumentReference\",\"ID\":\"notes.txt.blob\"}");
		conformance.call("contents", "copy", NOTES, other.child("mine", ContentScope.PRIVATE), ContentScope.PRIVATE,
				null);
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Target@odata.bind\":\"Contents('Applications')/Contents('Other')/PrivateContents('mine')\",\"Private\":true}");
	}

	@Test
	void movesAnEntry() {
		ContentPath other = ContentPath.applications().child("Other");
		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.Link\",\"ID\":\"ibm.extr\"}");
		Entry moved = conformance.call("contents", "move", PLANNING.child("ibm.extr"), other, ContentScope.PUBLIC,
				null);
		assertThat(moved).isInstanceOf(Link.class);
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key())
			.isEqualTo("POST Contents('Applications')/Contents('Planning')/Contents('ibm.extr')/tm1.Move");
		assertThat(post.text())
			.isEqualTo("{\"Target@odata.bind\":\"Contents('Applications')/Contents('Other')\",\"Private\":false}");

		tm1().json("{\"@odata.type\":\"#ibm.tm1.api.v1.Link\",\"ID\":\"renamed.extr\"}");
		conformance.call("contents", "move", PLANNING.child("ibm.extr"), other, ContentScope.PRIVATE, "renamed");
		assertThat(tm1().last().text()).isEqualTo(
				"{\"Target@odata.bind\":\"Contents('Applications')/Contents('Other')\",\"Private\":true,\"Name\":\"renamed\"}");
	}

	@Test
	void aMoveIntoAMissingFolderIsNotFound() {
		tm1().error(404, "278", "'Nowhere' can not be found in collection of type 'Entry'.");
		Throwable error = conformance.fail("contents", "move", NOTES, ContentPath.applications().child("Nowhere"),
				ContentScope.PUBLIC, null);
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void findsTheDocumentBehindAReferenceOrInBlobs() {
		tm1().json("{\"ID\":\"notes_20261122125939.txt\",\"Name\":\"notes_20261122125939.txt\",\"Size\":11,"
				+ "\"LastUpdated\":\"2026-11-22T12:59:39Z\"}");
		Document document = conformance.call("contents", "document", NOTES, ODataQuery.none());
		assertThat(document.size()).isEqualTo(11);
		assertThat(tm1().last().target()).isEqualTo(
				"Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document");

		tm1().json("{\"Size\":11}");
		conformance.call("contents", "document", ContentPath.blobs().child("notes_20261122125939.txt"),
				ODataQuery.none().select("Size"));
		assertThat(tm1().last().target())
			.isEqualTo("Contents('Blobs')/Contents('notes_20261122125939.txt')/tm1.Document?$select=Size");
	}

	@Test
	void readsADocumentsContent() {
		tm1().bytes("hello there".getBytes(StandardCharsets.UTF_8));
		byte[] content = conformance.call("contents", "content", NOTES);
		assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("hello there");
		ConformanceTm1.Recorded get = tm1().last();
		assertThat(get.key()).isEqualTo(
				"GET Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document/Content");

		tm1().bytes(new byte[] { 0, 1, 2, (byte) 0xff });
		byte[] blob = conformance.call("contents", "content", ContentPath.blobs().child("notes_1.bin"));
		assertThat(blob).containsExactly(0, 1, 2, 0xff);
		assertThat(tm1().last().target()).isEqualTo("Contents('Blobs')/Contents('notes_1.bin')/tm1.Document/Content");
	}

	@Test
	void anEmptyDocumentHasNoContent() {
		tm1().status(200);
		byte[] content = conformance.call("contents", "content", NOTES);
		assertThat(content).isEmpty();
	}

	@Test
	void uploadsADocumentsContent() {
		tm1().status(204);
		Object none = conformance.call("contents", "upload", NOTES, bytes("hello there"));
		assertThat(none).isNull();
		ConformanceTm1.Recorded put = tm1().last();
		assertThat(put.key()).isEqualTo(
				"PUT Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')/tm1.DocumentReference/Document/Content");
		assertThat(put.text()).isEqualTo("hello there");

		tm1().status(204);
		conformance.call("contents", "upload", ContentPath.blobs().child("notes_1.txt"), bytes("x"));
		assertThat(tm1().last().key()).isEqualTo("PUT Contents('Blobs')/Contents('notes_1.txt')/tm1.Document/Content");
	}

}
