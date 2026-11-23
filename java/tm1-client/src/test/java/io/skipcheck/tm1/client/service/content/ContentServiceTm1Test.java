package io.skipcheck.tm1.client.service.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.model.CubeReference;
import io.skipcheck.tm1.client.model.CubeReferencePatch;
import io.skipcheck.tm1.client.model.DocumentPatch;
import io.skipcheck.tm1.client.model.DocumentReference;
import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.model.Folder;
import io.skipcheck.tm1.client.model.FolderPatch;
import io.skipcheck.tm1.client.model.Link;
import io.skipcheck.tm1.client.model.LinkPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** ContentService: the contents tree, and what TM1 does with documents. */
@RequiresTm1
@SuppressWarnings("deprecation") // Entry.ID is deprecated in schema 11.5.0, yet it's the
									// key
class ContentServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
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
		assertThat(tm1.contents().roots(ODataQuery.none()).map(Entry::name).collectList().block(WAIT))
			.contains("Applications", "Blobs");
	}

	@Test
	void keepsADocumentInBlobsBehindAReference() {
		String name = unique();
		ContentPath folder = ContentPath.applications().child(name);
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name(name).build())
			.block(WAIT);
		try {
			Entry made = tm1.contents()
				.create(folder, ContentScope.PUBLIC, DocumentPatch.builder().name("notes.txt").build())
				.block(WAIT);
			assertThat(made).as("a document made in an application is a reference")
				.isInstanceOf(DocumentReference.class);
			assertThat(made.id()).as("found by ID, the name plus a suffix").isEqualTo("notes.txt.blob");
			ContentPath notes = folder.child(made);
			assertThatThrownBy(() -> tm1.contents().get(folder.child("notes.txt"), ODataQuery.none()).block(WAIT))
				.as("not by name")
				.isInstanceOf(Tm1NotFoundException.class);

			tm1.contents().upload(notes, bytes("hello there")).block(WAIT);
			assertThat(text(tm1.contents().content(notes))).isEqualTo("hello there");
			var document = tm1.contents().document(notes, ODataQuery.none()).block(WAIT);
			assertThat(document.size()).isEqualTo(11);
			assertThat(document.name()).as("named by TM1, with a timestamp").startsWith("notes_").endsWith(".txt");
			ContentPath blob = ContentPath.blobs().child(document.id());
			assertThat(text(tm1.contents().content(blob))).isEqualTo("hello there");

			Entry copy = tm1.contents().copy(notes, folder, ContentScope.PUBLIC, "copy.txt").block(WAIT);
			assertThat(copy.id()).isEqualTo("copy.txt.blob");
			assertThat(tm1.contents().document(folder.child(copy.id()), ODataQuery.none()).block(WAIT).id())
				.as("a copy refers to the same document")
				.isEqualTo(document.id());
			tm1.contents().delete(notes).block(WAIT);
			assertThat(text(tm1.contents().content(blob))).as("kept while the copy refers to it")
				.isEqualTo("hello there");
		}
		finally {
			tm1.contents().delete(folder).block(WAIT);
		}
	}

	@Test
	void makesLinksAndReferencesAndMovesThem() {
		String name = unique();
		ContentPath folder = ContentPath.applications().child(name);
		ContentPath other = ContentPath.applications().child(name + "-2");
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name(name).build())
			.block(WAIT);
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name(name + "-2").build())
			.block(WAIT);
		try {
			Entry link = tm1.contents()
				.create(folder, ContentScope.PUBLIC, LinkPatch.builder().name("ibm").url("https://www.ibm.com").build())
				.block(WAIT);
			assertThat(link.id()).isEqualTo("ibm.extr");
			Entry reference = tm1.contents()
				.create(folder, ContentScope.PUBLIC,
						CubeReferencePatch.builder()
							.name("}ClientGroups")
							.bindCube(ODataPath.of("Cubes").key("}ClientGroups"))
							.build())
				.block(WAIT);
			assertThat(reference.id()).isEqualTo("}ClientGroups.cube");

			List<Entry> listed = tm1.contents()
				.contents(folder, ContentScope.PUBLIC, ODataQuery.none().expand("tm1.CubeReference/Cube($select=Name)"))
				.collectList()
				.block(WAIT);
			assertThat(listed).filteredOn(CubeReference.class::isInstance)
				.singleElement()
				.extracting(e -> ((CubeReference) e).cube().name())
				.isEqualTo("}ClientGroups");
			assertThat(tm1.contents().get(folder.child(link.id()), ODataQuery.none()).block(WAIT))
				.isInstanceOfSatisfying(Link.class, l -> assertThat(l.url()).isEqualTo("https://www.ibm.com"));
			Folder parent = tm1.contents().containedBy(folder.child(link.id()), ODataQuery.none()).block(WAIT);
			assertThat(parent.id()).isEqualTo(name);

			assertThatThrownBy(() -> tm1.session()
				.execute(Tm1Request.patch(folder.child(link.id()).path(), Map.of("Name", "renamed")))
				.block(WAIT)).as("entries can't be changed").isInstanceOf(Tm1BadRequestException.class);

			Entry moved = tm1.contents().move(folder.child(link.id()), other, ContentScope.PUBLIC, null).block(WAIT);
			assertThat(moved.id()).isEqualTo("ibm.extr");
			assertThat(tm1.contents()
				.contents(other, ContentScope.PUBLIC, ODataQuery.none())
				.map(Entry::id)
				.collectList()
				.block(WAIT)).containsExactly("ibm.extr");

			tm1.contents().create(folder, ContentScope.PRIVATE, FolderPatch.builder().name("mine").build()).block(WAIT);
			assertThat(tm1.contents()
				.contents(folder, ContentScope.PRIVATE, ODataQuery.none())
				.map(Entry::id)
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
	void refusesAChunkedUpload(Tm1ServerContainer server) throws Exception {
		String name = unique();
		ContentPath folder = ContentPath.applications().child(name);
		tm1.contents()
			.create(ContentPath.applications(), ContentScope.PUBLIC, FolderPatch.builder().name(name).build())
			.block(WAIT);
		try {
			Entry made = tm1.contents()
				.create(folder, ContentScope.PUBLIC, DocumentPatch.builder().name("notes.txt").build())
				.block(WAIT);
			String auth = Base64.getEncoder()
				.encodeToString((server.getUsername() + ":" + server.getPassword()).getBytes(StandardCharsets.UTF_8));
			HttpRequest chunked = HttpRequest
				.newBuilder(URI.create(server.getRestUrl() + folder.child(made.id()).path()
						+ "/tm1.DocumentReference/Document/Content"))
				.header("Authorization", "Basic " + auth)
				.header("Content-Type", "application/octet-stream")
				// a publisher of unknown length is sent chunked
				.PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString("hello")))
				.build();
			assertThat(HttpClient.newHttpClient().send(chunked, HttpResponse.BodyHandlers.discarding()).statusCode())
				.isEqualTo(400);
		}
		finally {
			tm1.contents().delete(folder).block(WAIT);
		}
	}

}
