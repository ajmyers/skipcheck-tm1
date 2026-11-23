// SPDX-License-Identifier: Apache-2.0

using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;

namespace Skipcheck.Tm1.Client.Services;

/// <summary>Translated attribute values: an <see cref="AttributeOwner"/>'s <c>LocalizedAttributes</c>, one
/// per locale, keyed by locale ID (<c>fr</c>, <c>de-CH</c>). The values are in <c>Attributes</c>:
/// <c>Caption</c>, and every other attribute as a dynamic property.
/// <para>Only attributes the owner's kind has can be translated (see <see cref="AttributeService"/>):
/// <c>Caption</c> too, once it's defined. A member's translations are its element's, and read only:
/// write them through <see cref="AttributeOwner.Element"/>.</para>
/// <para>TM1 (11.8) creates a locale's translations once and reads them, and that's all: it supports no
/// PATCH or DELETE on them and refuses to create a locale twice. Changing or removing translations goes
/// through the <c>}Localized…Attributes</c> control cubes, as cell writes.</para>
/// </summary>
public sealed class LocalizedAttributeService : Tm1Service
{
    /// <summary>A service that sends its requests through <paramref name="session"/>.</summary>
    public LocalizedAttributeService(Tm1Session session)
        : base(session) { }

    /// <summary><c>GET …/LocalizedAttributes</c>: the owner's translations, one per locale, streamed.</summary>
    [Tm1Endpoint("Navigation Dimension/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Element/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Member/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Subset/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Cube/LocalizedAttributes")]
    [Tm1Endpoint("Navigation View/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Process/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Chore/LocalizedAttributes")]
    public IAsyncEnumerable<LocalizedAttributes> ListAsync(AttributeOwner owner, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.StreamAsync(Tm1Request.List<LocalizedAttributes>(Localized(owner), query), cancellationToken);

    /// <summary><c>GET …/LocalizedAttributes('locale')</c>: the translations for the locale, or a
    /// <c>Tm1NotFoundException</c> if there are none.</summary>
    [Tm1Endpoint("Navigation Dimension/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Element/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Member/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Subset/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Cube/LocalizedAttributes")]
    [Tm1Endpoint("Navigation View/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Process/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Chore/LocalizedAttributes")]
    public Task<LocalizedAttributes> GetAsync(AttributeOwner owner, string locale, ODataQuery? query = null, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Get<LocalizedAttributes>(Localized(owner).Key(Require(locale, nameof(locale))), query), cancellationToken);

    /// <summary><c>POST …/LocalizedAttributes</c>: adds translations for a locale, its <c>LocaleID</c> and
    /// the translated <c>Attributes</c>; returns them as created.</summary>
    [Tm1Endpoint("Navigation Dimension/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Hierarchy/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Element/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Member/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Subset/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Cube/LocalizedAttributes")]
    [Tm1Endpoint("Navigation View/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Process/LocalizedAttributes")]
    [Tm1Endpoint("Navigation Chore/LocalizedAttributes")]
    public Task<LocalizedAttributes> CreateAsync(AttributeOwner owner, LocalizedAttributesPatch translations, CancellationToken cancellationToken = default) =>
        Session.ExecuteAsync(Tm1Request.Post<LocalizedAttributes>(Localized(owner), Require(translations, nameof(translations))), cancellationToken);

    private static ODataPath Localized(AttributeOwner owner) => Require(owner, nameof(owner)).Path.Nav("LocalizedAttributes");
}
