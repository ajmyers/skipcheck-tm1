// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import io.skipcheck.tm1.client.LiveServer;
import io.skipcheck.tm1.client.LiveTm1;
import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.CubeReference;
import io.skipcheck.tm1.client.model.CubeReferenceCreate;
import io.skipcheck.tm1.client.model.Document;
import io.skipcheck.tm1.client.model.DocumentCreate;
import io.skipcheck.tm1.client.model.DocumentReference;
import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.model.Folder;
import io.skipcheck.tm1.client.model.FolderCreate;
import io.skipcheck.tm1.client.model.Link;
import io.skipcheck.tm1.client.model.LinkCreate;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** ContentService: the contents tree, and what TM1 does with documents. */
@LiveTm1
@SuppressWarnings("deprecation") // Entry.ID is deprecated in schema 11.5.0, yet it's the
									// key
class ContentServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(LiveServer server) {
		tm1 = server.clientBuilder().build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	private static String unique() {
		return "tm1-client-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static Flux<DataBuffer> bytes(String text) {
		return Flux.defer(
				() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8))));
	}

	private static String text(Flux<DataBuffer> content) {
		return DataBufferUtils.join(content).map(buffer -> {
			try {
				return buffer.toString(StandardCharsets.UTF_8);
			}
			finally {
				DataBufferUtils.release(buffer);
			}
		}).block(WAIT);
	}

	@Test
	void listsTheRoots() {
		List<String> roots = tm1.contents().roots(ODataQuery.none()).map(Entry::name).collectList().block(WAIT);
		if (ServerAssumptions.isV12(tm1)) {
			assertThat(roots).contains("Applications", "Files", "Home", "Session");
		}
		else {
			assertThat(roots).contains("Applications", "Blobs");
		}
	}

	@Test
	void keepsADocumentInBlobsBehindAReference() {
		boolean v12 = ServerAssumptions.isV12(tm1);
		String name = unique();
		ContentPath folder = ContentPath.applications().child(name);
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
			.block(WAIT);
		// TM1 keeps a document as }externals/<name>_<second>.<ext>, server-wide: two
		// made with one name in one second collide (400, code 226), so each test names
		// its own
		String doc = name + "-notes";
		try {
			Entry made = tm1.contents()
				.create(folder, ContentScope.PUBLIC, DocumentCreate.builder().name(doc + ".txt").build())
				.block(WAIT);
			assertThat(made).as("a document made in an application is a reference")
				.isInstanceOf(DocumentReference.class);
			if (v12) {
				assertThat(made.id()).as("TM1 v12 omits deprecated Entry.ID").isNull();
				assertThat(made.name()).isEqualTo(doc + ".txt");
			}
			else {
				assertThat(made.id()).as("found by ID, the name plus a suffix").isEqualTo(doc + ".txt.blob");
			}
			ContentPath notes = folder.child(made);
			if (!v12) {
				assertThatThrownBy(() -> tm1.contents().get(folder.child(doc + ".txt"), ODataQuery.none()).block(WAIT))
					.as("not by name")
					.isInstanceOf(Tm1NotFoundException.class);
			}

			tm1.contents().upload(notes, bytes("hello there")).block(WAIT);
			assertThat(text(tm1.contents().content(notes))).isEqualTo("hello there");
			var document = tm1.contents().document(notes, ODataQuery.none()).block(WAIT);
			assertThat(document.size()).isEqualTo(11);
			assertThat(document.name()).as("named by TM1, with a timestamp").startsWith(doc + "_").endsWith(".txt");
			ContentPath blob = v12 ? null : ContentPath.blobs().child(document.id());
			if (blob != null) {
				assertThat(text(tm1.contents().content(blob))).isEqualTo("hello there");
			}

			Entry copy = tm1.contents().copy(notes, folder, ContentScope.PUBLIC, doc + "-copy.txt").block(WAIT);
			if (v12) {
				assertThat(copy.name()).isEqualTo(doc + "-copy.txt");
				assertThat(tm1.contents().document(folder.child(copy), ODataQuery.none()).block(WAIT).name())
					.as("TM1 v12 gives a copy its own backing document")
					.startsWith(doc + "-copy_")
					.endsWith(".txt");
			}
			else {
				assertThat(copy.id()).isEqualTo(doc + "-copy.txt.blob");
				assertThat(tm1.contents().document(folder.child(copy.id()), ODataQuery.none()).block(WAIT).id())
					.as("a copy refers to the same document")
					.isEqualTo(document.id());
			}
			tm1.contents().delete(notes).block(WAIT);
			assertThat(text(tm1.contents().content(blob != null ? blob : folder.child(copy))))
				.as("kept while the copy refers to it")
				.isEqualTo("hello there");
		}
		finally {
			tm1.contents().delete(folder).block(WAIT);
		}
	}

	@Test
	void makesLinksAndReferencesAndMovesThem() {
		boolean v12 = ServerAssumptions.isV12(tm1);
		String name = unique();
		ContentPath folder = ContentPath.applications().child(name);
		ContentPath other = ContentPath.applications().child(name + "-2");
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
			.block(WAIT);
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name + "-2").build())
			.block(WAIT);
		try {
			Entry link = tm1.contents()
				.create(folder, ContentScope.PUBLIC,
						LinkCreate.builder().name("ibm").url("https://www.ibm.com").build())
				.block(WAIT);
			assertThat(v12 ? link.name() : link.id()).isEqualTo(v12 ? "ibm" : "ibm.extr");
			Entry reference = tm1.contents()
				.create(folder, ContentScope.PUBLIC,
						CubeReferenceCreate.builder()
							.name("}ClientGroups")
							.bindCube(ODataPath.of("Cubes").key("}ClientGroups"))
							.build())
				.block(WAIT);
			assertThat(v12 ? reference.name() : reference.id()).isEqualTo(v12 ? "}ClientGroups" : "}ClientGroups.cube");

			List<Entry> listed = tm1.contents()
				.contents(folder, ContentScope.PUBLIC, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)"))
				.collectList()
				.block(WAIT);
			assertThat(listed).filteredOn(CubeReference.class::isInstance)
				.singleElement()
				.extracting(e -> ((CubeReference) e).cube().name())
				.isEqualTo("}ClientGroups");
			ContentPath linkPath = folder.child(link);
			assertThat(tm1.contents().get(linkPath, ODataQuery.none()).block(WAIT)).isInstanceOfSatisfying(Link.class,
					l -> assertThat(l.url()).isEqualTo("https://www.ibm.com"));
			Folder parent = tm1.contents().containedBy(linkPath, ODataQuery.none()).block(WAIT);
			assertThat(v12 ? parent.name() : parent.id()).isEqualTo(name);

			assertThatThrownBy(() -> tm1.session()
				.execute(Tm1Request.patch(linkPath.path(), Map.of("Name", "renamed")))
				.block(WAIT)).as("entries can't be changed").isInstanceOf(Tm1BadRequestException.class);

			Entry moved = tm1.contents().move(linkPath, other, ContentScope.PUBLIC, null).block(WAIT);
			assertThat(v12 ? moved.name() : moved.id()).isEqualTo(v12 ? "ibm" : "ibm.extr");
			assertThat(tm1.contents()
				.contents(other, ContentScope.PUBLIC, ODataQuery.none())
				.map(e -> v12 ? e.name() : e.id())
				.collectList()
				.block(WAIT)).containsExactly(v12 ? "ibm" : "ibm.extr");

			tm1.contents()
				.create(folder, ContentScope.PRIVATE, FolderCreate.builder().name("mine").build())
				.block(WAIT);
			assertThat(tm1.contents()
				.contents(folder, ContentScope.PRIVATE, ODataQuery.none())
				.map(e -> v12 ? e.name() : e.id())
				.collectList()
				.block(WAIT)).containsExactly("mine");
			assertThat(tm1.contents().get(folder.child("mine", ContentScope.PRIVATE), ODataQuery.none()).block(WAIT))
				.isInstanceOf(Folder.class);
			assertThat(tm1.contents().localizedAttributes(folder, ODataQuery.none()).collectList().block(WAIT))
				.isEmpty();
		}
		finally {
			Mono.when(tm1.contents().delete(folder), tm1.contents().delete(other)).block(WAIT);
		}
	}

	@Test
	void readsAndWritesDocumentsInFilesHomeAndSession() {
		assumeTrue(ServerAssumptions.isV12(tm1), "Files, Home and Session direct-document roots are TM1 v12");
		String filename = unique() + ".txt";
		for (ContentPath root : List.of(ContentPath.files(), ContentPath.home(), ContentPath.session())) {
			Entry created = tm1.contents()
				.create(root, ContentScope.PUBLIC, DocumentCreate.builder().name(filename).build())
				.block(WAIT);
			assertThat(created).isInstanceOf(Document.class);
			ContentPath doc = root.child(created);
			try {
				tm1.contents().upload(doc, bytes("direct content")).block(WAIT);
				assertThat(text(tm1.contents().content(doc))).isEqualTo("direct content");
				assertThat(tm1.contents().document(doc, ODataQuery.none()).block(WAIT).size()).isEqualTo(14);
			}
			finally {
				tm1.contents().delete(doc).block(WAIT);
			}
		}
	}

	@Test
	void refusesAChunkedUpload(LiveServer server) throws Exception {
		String name = unique();
		ContentPath folder = ContentPath.applications().child(name);
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderCreate.builder().name(name).build())
			.block(WAIT);
		try {
			// A name of its own: see keepsADocumentInBlobsBehindAReference
			Entry made = tm1.contents()
				.create(folder, ContentScope.PUBLIC, DocumentCreate.builder().name(name + "-notes.txt").build())
				.block(WAIT);
			HttpRequest chunked = server
				.authorize(HttpRequest.newBuilder(URI.create(
						server.getRestUrl() + folder.child(made).path() + "/tm1.DocumentReference/Document/Content")))
				.version(HttpClient.Version.HTTP_1_1)
				.header("Content-Type", "application/octet-stream")
				// a publisher of unknown length is sent chunked
				.PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString("hello")))
				.build();
			// TM1 11 refuses chunked uploads (400); TM1 v12's gateway accepts them (204)
			int expected = ServerAssumptions.isV12(tm1) ? 204 : 400;
			assertThat(server.httpClient().build().send(chunked, HttpResponse.BodyHandlers.discarding()).statusCode())
				.isEqualTo(expected);
		}
		finally {
			tm1.contents().delete(folder).block(WAIT);
		}
	}

}
