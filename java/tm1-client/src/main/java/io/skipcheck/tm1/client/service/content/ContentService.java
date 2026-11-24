// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.content;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Document;
import io.skipcheck.tm1.client.model.Entry;
import io.skipcheck.tm1.client.model.EntryCreate;
import io.skipcheck.tm1.client.model.Folder;
import io.skipcheck.tm1.client.model.LocalizedAttributes;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The contents tree, where Planning Analytics keeps applications: folders, documents,
 * links, and references to cubes, dimensions, subsets, views, processes and chores.
 * Entries are addressed by {@link ContentPath}.
 *
 * <p>
 * What TM1 (11.8) does here that you might not expect:
 * <ul>
 * <li>Entries are found by {@code ID}, not {@code Name} (see {@link ContentPath}).</li>
 * <li>A document made in an application becomes a {@code DocumentReference} there, to a
 * {@code Document} TM1 makes in {@code Blobs} with a timestamp in its name, such as
 * {@code notes_20261122125939.txt}. Its content goes through the reference
 * ({@link #content}, {@link #upload}). A copy of the reference refers to the same
 * document; deleting a reference leaves the document while another refers to it.</li>
 * <li>Entries can't be changed: TM1 refuses a PATCH ("PATCH not supported on this
 * resource"), so there's no update. To rename one, copy it with a new name and delete the
 * original.</li>
 * </ul>
 *
 * <p>
 * Build an application: a folder, a link and a cube reference:
 * {@snippet class = examples.ContentServiceExamples region = application}
 *
 * <p>
 * Upload a document from a file:
 * {@snippet class = examples.ContentServiceExamples region = upload}
 *
 * <p>
 * Download a document's content to a file:
 * {@snippet class = examples.ContentServiceExamples region = download}
 *
 * <p>
 * List the cubes an application refers to:
 * {@snippet class = examples.ContentServiceExamples region = cubes}
 */
public final class ContentService {

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ContentService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Contents}, streamed: the root folders, {@code Applications} and
	 * {@code Blobs}.
	 * @param query what to select, expand, filter and order
	 * @return the roots
	 */
	@Tm1Endpoint("EntitySet Contents")
	public Flux<Entry> roots(ODataQuery query) {
		return session.stream(Tm1Request.list(ODataPath.of("Contents"), query, Entry.class));
	}

	/**
	 * {@code GET} of one entry. A reference's object comes by a cast expand, such as
	 * {@code expand("tm1.CubeReference/Cube")}.
	 * @param entry where it is
	 * @param query what to select and expand
	 * @return the entry, of its own kind ({@code Folder}, {@code Link},
	 * {@code CubeReference} …), or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Contents")
	public Mono<Entry> get(ContentPath entry, ODataQuery query) {
		return session.execute(Tm1Request.get(entry.path(), query, Entry.class));
	}

	/**
	 * A folder's entries, streamed. References' objects come by a cast expand, such as
	 * {@code expand("tm1.CubeReference/Cube($select=Name)")}.
	 * @param folder the folder
	 * @param scope its public or private entries
	 * @param query what to select, expand, filter and order
	 * @return the entries, each of its own kind
	 */
	@Tm1Endpoint("Navigation Folder/Contents")
	@Tm1Endpoint("Navigation Folder/PrivateContents")
	@Tm1Endpoint("Navigation ChoreReference/Chore")
	@Tm1Endpoint("Navigation CubeReference/Cube")
	@Tm1Endpoint("Navigation DimensionReference/Dimension")
	@Tm1Endpoint("Navigation ProcessReference/Process")
	@Tm1Endpoint("Navigation SubsetReference/Subset")
	@Tm1Endpoint("Navigation ViewReference/View")
	public Flux<Entry> contents(ContentPath folder, ContentScope scope, ODataQuery query) {
		return session.stream(Tm1Request.list(folder.path().nav(scope.navigation()), query, Entry.class));
	}

	/**
	 * Makes an entry in a folder: a {@code FolderCreate}, {@code DocumentCreate},
	 * {@code LinkCreate} or a reference, such as a {@code CubeReferenceCreate} with
	 * {@code bindCube(ODataPath.of("Cubes").key("Sales"))}. A document made outside
	 * {@code Blobs} comes back as a {@code DocumentReference}, with an empty document
	 * behind it; {@link #upload} gives it content.
	 * @param folder the folder
	 * @param scope whether it's public or private
	 * @param entry the entry
	 * @return the entry as made, with the {@code ID} to find it by
	 */
	@Tm1Endpoint("Navigation Folder/Contents")
	@Tm1Endpoint("Navigation Folder/PrivateContents")
	public Mono<Entry> create(ContentPath folder, ContentScope scope, EntryCreate entry) {
		return session.execute(Tm1Request.post(folder.path().nav(scope.navigation()), entry, Entry.class));
	}

	/**
	 * {@code DELETE} of an entry, and everything in it if it's a folder.
	 * @param entry where it is
	 * @return completes when it's gone
	 */
	@Tm1Endpoint("EntitySet Contents")
	public Mono<Void> delete(ContentPath entry) {
		return session.execute(Tm1Request.delete(entry.path()));
	}

	/**
	 * {@code ContainedBy}: the folder an entry is in.
	 * @param entry where it is
	 * @param query what to select
	 * @return the folder
	 */
	@Tm1Endpoint("Navigation Entry/ContainedBy")
	public Mono<Folder> containedBy(ContentPath entry, ODataQuery query) {
		return session.execute(Tm1Request.get(entry.path().nav("ContainedBy"), query, Folder.class));
	}

	/**
	 * {@code LocalizedAttributes}, streamed: an entry's attributes in each locale.
	 * @param entry where it is
	 * @param query what to select and filter
	 * @return the localized attributes
	 */
	@Tm1Endpoint("Navigation Entry/LocalizedAttributes")
	public Flux<LocalizedAttributes> localizedAttributes(ContentPath entry, ODataQuery query) {
		return session
			.stream(Tm1Request.list(entry.path().nav("LocalizedAttributes"), query, LocalizedAttributes.class));
	}

	/**
	 * {@code tm1.Copy}: copies an entry into a folder. A copied document reference refers
	 * to the same document.
	 * @param entry where it is
	 * @param target the folder to copy it to
	 * @param scope whether the copy is public or private
	 * @param name the copy's name, or {@code null} to keep the entry's
	 * @return the copy
	 */
	@Tm1Endpoint("Action Entry/Copy")
	public Mono<Entry> copy(ContentPath entry, ContentPath target, ContentScope scope, @Nullable String name) {
		return relocate(Tm1Operations.ENTRY_COPY, entry, target, scope, name);
	}

	/**
	 * {@code tm1.Move}: moves an entry into a folder.
	 * @param entry where it is
	 * @param target the folder to move it to
	 * @param scope whether it's public or private there
	 * @param name its new name, or {@code null} to keep it
	 * @return the entry where it is now
	 */
	@Tm1Endpoint("Action Entry/Move")
	public Mono<Entry> move(ContentPath entry, ContentPath target, ContentScope scope, @Nullable String name) {
		return relocate(Tm1Operations.ENTRY_MOVE, entry, target, scope, name);
	}

	/**
	 * {@code Document}: the document a reference refers to, in {@code Blobs}.
	 * @param reference where the {@code DocumentReference} is
	 * @param query what to select
	 * @return the document, with its {@code Size} and {@code LastUpdated}
	 */
	@Tm1Endpoint("Navigation DocumentReference/Document")
	public Mono<Document> document(ContentPath reference, ODataQuery query) {
		return session.execute(Tm1Request.get(document(reference), query, Document.class));
	}

	/**
	 * A document's {@code Content}, streamed as it arrives. The path is a document
	 * reference, or a document in {@code Blobs}. Each buffer is the subscriber's to
	 * release, or to hand on, such as to {@code DataBufferUtils.write}.
	 * @param document where it is
	 * @return the bytes
	 */
	@Tm1Endpoint("Navigation DocumentReference/Document")
	public Flux<DataBuffer> content(ContentPath document) {
		return session.stream(Tm1Request.bytes(document(document).nav("Content")));
	}

	/**
	 * Replaces a document's {@code Content}. The path is a document reference, or a
	 * document in {@code Blobs}. TM1 (11.8) refuses a chunked upload, so the content is
	 * gathered and sent with its length: it's held in memory whole while it's sent. If
	 * the session has to log in again, {@code content} is subscribed again, so it should
	 * be one that can be, such as {@code DataBufferUtils.read} of a file.
	 * @param document where it is
	 * @param content the bytes
	 * @return completes when they're written
	 */
	@Tm1Endpoint("Navigation DocumentReference/Document")
	public Mono<Void> upload(ContentPath document, Publisher<DataBuffer> content) {
		return session.execute(Tm1Request.put(document(document).nav("Content"), content));
	}

	private static ODataPath document(ContentPath entry) {
		return entry.inBlobs() ? entry.path().cast("tm1.Document")
				: entry.path().cast("tm1.DocumentReference").nav("Document");
	}

	private Mono<Entry> relocate(Tm1Operation operation, ContentPath entry, ContentPath target, ContentScope scope,
			@Nullable String name) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Target@odata.bind", target.path().toString());
		body.put("Private", scope == ContentScope.PRIVATE);
		if (name != null) {
			body.put("Name", name);
		}
		return session.execute(Tm1Request.post(entry.path().action(operation.name()), body, Entry.class)
			.requires(operation.capability()));
	}

}
