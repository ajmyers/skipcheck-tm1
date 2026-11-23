// SPDX-License-Identifier: Apache-2.0

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Where an entry is in the contents tree: a root folder, then each folder's child by ID.
/// <code>
/// // Children by ID, which for most entries is the name plus a suffix for the kind
/// var notes = ContentPath.Applications().Child("Planning").Child("notes.txt.blob");
/// // Contents('Applications')/Contents('Planning')/Contents('notes.txt.blob')
/// </code>
/// <para>Children are found by their <c>ID</c>, not their <c>Name</c>, though <c>$metadata</c> keys
/// entries by <c>Name</c>: TM1 (11.8) gives most entries an ID of the name plus a suffix for its kind,
/// such as <c>notes.txt.blob</c> for a document, <c>ibm.extr</c> for a link and
/// <c>}ClientGroups.cube</c> for a cube reference. A folder's ID is its name. The ID is in the entry
/// TM1 returns when it's made. <c>$metadata</c> deprecates <c>Entry.ID</c> in schema 11.5.0, but TM1
/// 11.8 still keys by it. Paths are immutable.</para>
/// </summary>
public sealed class ContentPath : IEquatable<ContentPath>
{
    private readonly IReadOnlyList<(string Id, ContentScope Scope)> _steps;

    private ContentPath(IReadOnlyList<(string Id, ContentScope Scope)> steps)
    {
        _steps = steps;
    }

    /// <summary>A root folder: <c>Contents('id')</c>, such as <c>Applications</c>.</summary>
    public static ContentPath Root(string id) => new([(id ?? throw new ArgumentNullException(nameof(id)), ContentScope.Public)]);

    /// <summary>The <c>Applications</c> folder, where applications are built.</summary>
    public static ContentPath Applications() => Root("Applications");

    /// <summary>The <c>Blobs</c> folder, holding the documents behind the document references in applications.</summary>
    public static ContentPath Blobs() => Root("Blobs");

    /// <summary>A public child of this folder: <c>…/Contents('id')</c>.</summary>
    public ContentPath Child(string id) => Child(id, ContentScope.Public);

    /// <summary>A public child of this folder, as TM1 returned it, such as from
    /// <c>ContentService.CreateAsync</c>: found by its <c>ID</c>, which <c>$metadata</c> deprecates but
    /// TM1 (11.8) still keys entries by, so callers needn't read it.</summary>
    /// <exception cref="ArgumentException">The entry has no ID (it was selected away).</exception>
    public ContentPath Child(Entry entry) => Child(entry, ContentScope.Public);

    /// <summary>A child of this folder as TM1 returned it, public or private: see <see cref="Child(Entry)"/>.</summary>
    /// <exception cref="ArgumentException">The entry has no ID.</exception>
    public ContentPath Child(Entry entry, ContentScope scope)
    {
        ArgumentNullException.ThrowIfNull(entry);
        var id = entry.Id ?? throw new ArgumentException("The entry has no ID to find it by: " + entry.Name, nameof(entry));
        return Child(id, scope);
    }

    /// <summary>A child of this folder, public or private.</summary>
    public ContentPath Child(string id, ContentScope scope) => new([.. _steps, (id ?? throw new ArgumentNullException(nameof(id)), scope)]);

    /// <summary>Whether this is in the <c>Blobs</c> folder, where documents themselves are.</summary>
    public bool InBlobs() => _steps[0].Id == "Blobs";

    /// <summary>The OData path to the entry, such as <c>Contents('Applications')/Contents('Planning')</c>.</summary>
    public ODataPath Path()
    {
        var path = ODataPath.Of("Contents").Key(_steps[0].Id);
        foreach (var step in _steps.Skip(1))
        {
            path = path.Nav(step.Scope.Navigation()).Key(step.Id);
        }
        return path;
    }

    /// <summary>The IDs from the root, such as <c>Applications/Planning/notes.txt.blob</c>.</summary>
    public override string ToString()
    {
        var text = new StringBuilder();
        foreach (var (id, scope) in _steps)
        {
            if (text.Length > 0)
            {
                text.Append('/');
            }
            text.Append(scope == ContentScope.Private ? "(private) " : "").Append(id);
        }
        return text.ToString();
    }

    /// <inheritdoc/>
    public bool Equals(ContentPath? other) => other is not null && _steps.SequenceEqual(other._steps);

    /// <inheritdoc/>
    public override bool Equals(object? obj) => obj is ContentPath other && Equals(other);

    /// <inheritdoc/>
    public override int GetHashCode() => _steps.Aggregate(17, (h, s) => HashCode.Combine(h, s.Id, s.Scope));
}
