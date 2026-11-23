using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Auth;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.OData;
using Skipcheck.Tm1.Client.Session;
using Skipcheck.Tm1.Client.Version;

namespace Skipcheck.Tm1.Client.Tests.Runtime;

/// <summary>The Java client's <c>Tm1ErrorsTest</c>: reading TM1's errors and classifying them.</summary>
public class Tm1ErrorsTest
{
    [Fact]
    public void ReadsTheODataErrorBody()
    {
        var e = Error("{\"error\":{\"code\":\"278\",\"message\":\"Boom\",\"target\":\"Name\",\"details\":[{\"code\":\"1\",\"message\":\"inner\"}]}}")!;
        Assert.Equal("278", e.Code);
        Assert.Equal("Boom", e.Message);
        Assert.Equal("Name", e.Target);
        Assert.Equal(["inner"], e.Details.Select(d => d.Message));
        Assert.Null(Error("{\"value\":[]}"));
        Assert.Null(Error("[]"));
    }

    /// <summary>TM1 11.8 sends impersonation errors as plain text: a status line, then the message.</summary>
    [Fact]
    public void ReadsAndClassifiesPlainTextErrors()
    {
        var unknown = ODataError.FromText("Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n");
        Assert.NotNull(unknown);
        Assert.Equal("The user specified in the impersonation request does not exist.", unknown.Message);
        Assert.Empty(unknown.Code);
        var classifier = Tm1ErrorClassifier.Defaults();
        Assert.Equal(Tm1ErrorKind.UnknownUser, classifier.Classify(400, unknown));
        Assert.Equal(
            Tm1ErrorKind.ImpersonationRefused,
            classifier.Classify(403, ODataError.FromText("Error: Forbidden\r\nYou have insufficient rights to impersonate the user specified!\r\n"))
        );
        Assert.Equal(
            Tm1ErrorKind.ImpersonationRefused,
            classifier.Classify(
                403,
                ODataError.FromText("Error: Forbidden\r\nYou need to be a (data-)administrator to be able to impersonate another user.\r\n")
            )
        );
        Assert.Null(ODataError.FromText("Error: Unauthorized\r\n"));
        Assert.Equal(1001, ODataError.FromText(new string('x', 5000))!.Message.Length);
    }

    [Fact]
    public void ClassifiesCapturedResponses()
    {
        // GET Cubes('NoSuchCube')
        var missing = Api(404, "{\"error\":{\"code\":\"278\",\"message\":\"'NoSuchCube' can not be found in collection of type 'Cube'.\"}}");
        Assert.IsType<Tm1NotFoundException>(missing);
        Assert.Equal(Tm1ErrorKind.NotFound, missing.Kind);
        Assert.Equal("TM1 returned 404: 'NoSuchCube' can not be found in collection of type 'Cube'. [code 278] (GET Cubes('NoSuchCube'))", missing.Message);
        // $search
        Assert.IsType<Tm1NotImplementedException>(Api(501, "{\"error\":{\"code\":\"278\",\"message\":\"$search not implemented.\"}}"));
        // PATCH with a stale If-Match
        Assert.IsType<Tm1PreconditionFailedException>(Api(412, "{\"error\":{\"code\":\"278\",\"message\":\"\"}}"));
        // Multipart $batch
        var batch = Api(
            400,
            "{\"error\":{\"code\":\"278\",\"message\":\"The content type specified is not supported. Please use JSON (application/json) as the content type.\"}}"
        );
        Assert.IsType<Tm1BadRequestException>(batch);
        Assert.Equal(Tm1ErrorKind.Other, batch.Kind);
    }

    [Fact]
    public void ClassifiesKnownMessages()
    {
        Assert.Equal(Tm1ErrorKind.ImpersonationRefused, Api(403, Msg("You have insufficient rights to impersonate the user specified!")).Kind);
        Assert.Equal(Tm1ErrorKind.UnknownUser, Api(400, Msg("The user specified in the impersonation request does not exist")).Kind);
        Assert.Equal(Tm1ErrorKind.Cancelled, Api(400, Msg("TM1UserException: Cancel")).Kind);
        Assert.Equal(Tm1ErrorKind.DataSpreadFailed, Api(400, Msg("DataSpreadFailed")).Kind);
        Assert.Equal(
            Tm1ErrorKind.InvalidUniqueName,
            Api(400, "{\"error\":{\"code\":\"248\",\"message\":\"Invalid element unique name specified in dimensional context\"}}").Kind
        );
        Assert.IsType<Tm1ConflictException>(Api(400, Msg("Dimension 'Region' already exists")));
        Assert.IsType<Tm1NotFoundException>(Api(400, Msg("ObjectNotFound")));
        Assert.Equal(Tm1ErrorKind.Locked, Api(400, Msg("Object is locked by another user")).Kind);
        Assert.Equal(Tm1ErrorKind.InUse, Api(400, Msg("Subset is used by a view and cannot be deleted")).Kind);
        // "Blocked" isn't "locked"
        Assert.Equal(Tm1ErrorKind.Other, Api(400, Msg("Blocked")).Kind);
    }

    [Fact]
    public void FallsBackOnTheStatus()
    {
        Assert.IsType<Tm1AuthenticationException>(Api(401, ""));
        Assert.IsType<Tm1ForbiddenException>(Api(403, ""));
        Assert.IsType<Tm1ConflictException>(Api(409, ""));
        Assert.IsType<Tm1PreconditionFailedException>(Api(428, ""));
        Assert.IsType<Tm1ServerException>(Api(503, ""));
        Assert.IsType<Tm1BadRequestException>(Api(418, ""));
        Assert.Equal(typeof(Tm1ApiException), Tm1ApiException.Of(302, null, "GET", "x", Tm1ErrorClassifier.Defaults()).GetType());
    }

    [Fact]
    public void LetsCallersAddRules()
    {
        var custom = Tm1ErrorClassifier.Defaults().WithRule(Tm1ErrorClassifier.Rule.Message("frozen", Tm1ErrorKind.Locked));
        Assert.Equal(Tm1ErrorKind.Locked, custom.Classify(400, Error(Msg("Cube is frozen"))));
        Assert.Equal(Tm1ErrorKind.Other, Tm1ErrorClassifier.Defaults().Classify(400, Error(Msg("Cube is frozen"))));
    }

    [Fact]
    public void SaysWhetherARetryCouldHelpAndWhetherTheRequestMayHaveApplied()
    {
        Assert.True(Api(503, "").IsRetryable());
        Assert.True(Api(503, "").MayHaveApplied());
        Assert.False(Api(400, "").IsRetryable());
        Assert.False(Api(400, "").MayHaveApplied());
        Assert.False(Api(501, "").MayHaveApplied());
        var connect = new Tm1TransportException(Tm1TransportException.Phase.Connect, false, "POST", "x", null);
        Assert.True(connect.IsRetryable());
        Assert.False(connect.MayHaveApplied());
        var postTimedOut = new Tm1TransportException(Tm1TransportException.Phase.Response, false, "POST", "x", null);
        Assert.False(postTimedOut.IsRetryable());
        Assert.True(postTimedOut.MayHaveApplied());
        Assert.True(new Tm1TransportException(Tm1TransportException.Phase.Response, true, "GET", "x", null).IsRetryable());
        var protocol = new Tm1ProtocolException("bad", null, "GET", "x", null);
        Assert.False(protocol.IsRetryable());
        Assert.True(protocol.MayHaveApplied());
        Assert.Equal("bad (GET x)", protocol.Message);
    }

    private static Tm1ApiException Api(int status, string body) =>
        Tm1ApiException.Of(status, body.Length == 0 ? null : Error(body), "GET", "Cubes('NoSuchCube')", Tm1ErrorClassifier.Defaults());

    private static ODataError? Error(string json) => ODataError.From(JsonDocument.Parse(json).RootElement);

    private static string Msg(string message) => "{\"error\":{\"code\":\"278\",\"message\":\"" + message + "\"}}";
}

/// <summary>The Java client's <c>Tm1AuthenticatorTest</c>.</summary>
public class Tm1AuthenticatorTest
{
    [Fact]
    public async Task WritesBasicCredentials()
    {
        var basic = Tm1Authenticator.Basic("admin", "apple");
        Assert.Equal("Basic " + Base64("admin:apple"), await basic.AuthorizationAsync());
        Assert.Equal("Basic " + Base64("admin:"), await Tm1Authenticator.Basic("admin", null).AuthorizationAsync());
        Assert.Equal("Basic admin", basic.Describe());
    }

    [Fact]
    public async Task WritesCamNamespaceCredentials()
    {
        var cam = Tm1Authenticator.CamNamespace("alice", "secret", "LDAP");
        Assert.Equal("CAMNamespace " + Base64("alice:secret:LDAP"), await cam.AuthorizationAsync());
        Assert.Equal("CAMNamespace LDAP/alice", cam.Describe());
    }

    [Fact]
    public async Task AsksForAFreshPassportOnEveryLogin()
    {
        var issued = 0;
        var cam = Tm1Authenticator.CamPassport(_ => ValueTask.FromResult("p" + Interlocked.Increment(ref issued)));
        Assert.Equal("CAMPassport p1", await cam.AuthorizationAsync());
        Assert.Equal("CAMPassport p2", await cam.AuthorizationAsync());
        Assert.Equal("CAMPassport", cam.Describe());
    }

    [Fact]
    public void NeverShowsCredentialsInToString()
    {
        Assert.DoesNotContain("apple", Tm1Authenticator.Basic("admin", "apple").ToString());
        Assert.DoesNotContain(Base64("admin:apple"), Tm1Authenticator.Basic("admin", "apple").ToString());
        Assert.DoesNotContain("secret", Tm1Authenticator.CamNamespace("alice", "secret", "LDAP").ToString());
        Assert.DoesNotContain("passport-value", Tm1Authenticator.CamPassport(_ => ValueTask.FromResult("passport-value")).ToString());
    }

    private static string Base64(string text) => Convert.ToBase64String(Encoding.UTF8.GetBytes(text));
}

/// <summary>The Java client's <c>CognosPassportsTest</c>, against a fake Cognos dispatcher.</summary>
public class CognosPassportsTest
{
    private readonly FakeCognos _cognos = new();

    private const string Dispatcher = "http://cognos.test:9300";

    [Fact]
    public async Task LogsOnForAFreshPassportEachTime()
    {
        var passports = CognosPassports.Password(Dispatcher + "/bi/", "LDAP", "viewer", "viewerpw", _cognos);
        Assert.Equal("passport-1", await passports(default));
        Assert.Equal("passport-2", await passports(default));
        Assert.All(_cognos.Bodies, body =>
        {
            Assert.StartsWith("PUT http://cognos.test:9300/api/v1/session ", body);
            Assert.Contains("{\"name\":\"CAMNamespace\",\"value\":\"LDAP\"}", body);
            Assert.Contains("\"CAMUsername\"", body);
            Assert.Contains("\"viewerpw\"", body);
        });
    }

    [Fact]
    public async Task SendsAnIdTokenRatherThanAPassword()
    {
        var passports = CognosPassports.IdToken(Dispatcher, "OIDC", _ => ValueTask.FromResult("eyJ.token"), _cognos);
        Assert.Equal("passport-1", await passports(default));
        var body = Assert.Single(_cognos.Bodies);
        Assert.Contains("CAMOIDCIdToken", body);
        Assert.Contains("eyJ.token", body);
    }

    [Fact]
    public async Task RefusesWrongCredentialsAsRejected()
    {
        var passports = CognosPassports.Password(Dispatcher, "LDAP", "viewer", "wrong", _cognos);
        var e = await Assert.ThrowsAsync<Tm1AuthenticationException>(async () => await passports(default));
        Assert.Equal(Tm1ErrorKind.CredentialRejected, e.Kind);
    }

    [Fact]
    public async Task DescribesWithoutTheSecret()
    {
        var cam = Tm1Authenticator.CognosLogon(Dispatcher, "LDAP", "viewer", "viewerpw", _cognos);
        Assert.Equal("CAMPassport from Cognos LDAP/viewer", cam.Describe());
        Assert.DoesNotContain("viewerpw", cam.ToString());
        Assert.Equal("CAMPassport passport-1", await cam.AuthorizationAsync());
        Assert.Equal(
            "CAMPassport from Cognos OIDC (ID token)",
            Tm1Authenticator.CognosIdToken(Dispatcher, "OIDC", _ => ValueTask.FromResult("t"), _cognos).Describe()
        );
    }

    [Fact]
    public async Task LogsInToTm1WithThePassport()
    {
        var tm1 = new ScriptedTm1().On("GET Server", _ => ScriptedTm1.Json(200, "{}"));
        var session = tm1.SessionWithOwnCredentials(b => b.Authenticator(Tm1Authenticator.CognosLogon(Dispatcher, "LDAP", "viewer", "viewerpw", _cognos)));
        await session.ExecuteAsync(Tm1Request.Get<JsonElement>(ODataPath.Of("Server")));
        Assert.Equal("CAMPassport passport-1", tm1.Requests(ScriptedTm1.Login)[0].Authorization);
    }

    /// <summary>Cognos's session resource: a <c>cam_passport</c> cookie for a logon, 401 for the password <c>wrong</c>.</summary>
    private sealed class FakeCognos : HttpMessageHandler
    {
        private int _logons;

        public List<string> Bodies { get; } = new();

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = await request.Content!.ReadAsStringAsync(cancellationToken);
            lock (Bodies)
            {
                Bodies.Add($"{request.Method} {request.RequestUri} {body}");
            }
            if (body.Contains("\"wrong\"", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.Unauthorized) { Content = new StringContent("{\"errorCodeString\":\"camAuthUserRecoverable\"}") };
            }
            var response = new HttpResponseMessage(HttpStatusCode.Created);
            response.Headers.TryAddWithoutValidation("Set-Cookie", $"cam_passport=passport-{Interlocked.Increment(ref _logons)}; Path=/; HttpOnly");
            return response;
        }
    }
}

/// <summary>The Java client's <c>ODataQueryTest</c>.</summary>
public class ODataQueryTest
{
    [Fact]
    public void IsEmptyByDefault()
    {
        Assert.Empty(ODataQuery.None().ToQueryString());
        Assert.True(ODataQuery.None().IsEmpty());
    }

    [Fact]
    public void WritesOptionsInAFixedOrder()
    {
        var query = ODataQuery.None().Top(10).WithCount().OrderBy("Name").Skip(5).Filter(Filter.Raw("Level eq 0")).Expand("Hierarchies").Select("Name", "Type");
        Assert.Equal("$select=Name,Type&$expand=Hierarchies&$filter=Level eq 0&$orderby=Name&$top=10&$skip=5&$count=true", query.ToString());
    }

    [Fact]
    public void NestsExpandOptionsWithSemicolons()
    {
        var query = ODataQuery
            .None()
            .Select("Name")
            .Expand("Hierarchies", h => h.Select("Name").Expand("Elements", e => e.Select("Name").Top(1)))
            .Expand("Cube");
        Assert.Equal("$select=Name&$expand=Hierarchies($select=Name;$expand=Elements($select=Name;$top=1)),Cube", query.ToString());
        // '=' inside a nested option is encoded; TM1 accepts it
        Assert.Equal("$select=Name&$expand=Hierarchies($select%3DName;$expand%3DElements($select%3DName;$top%3D1)),Cube", query.ToQueryString());
    }

    [Fact]
    public void AppliesNestedOptionsInAnyOrder() =>
        Assert.Equal(
            "$expand=Hierarchies($select=Name;$orderby=Name;$top=1)",
            ODataQuery.None().Expand("Hierarchies", h => h.Top(1).Select("Name").OrderBy("Name")).ToString()
        );

    [Fact]
    public void MergesRepeatedExpandsOfOneNavigation() =>
        Assert.Equal(
            "$expand=Hierarchies($select=Name;$top=2)",
            ODataQuery.None().Expand("Hierarchies", h => h.Select("Name")).Expand("Hierarchies", h => h.Top(2)).ToString()
        );

    [Fact]
    public void OrdersDescending() => Assert.Equal("$orderby=TimeStamp desc,ID", ODataQuery.None().OrderByDescending("TimeStamp").OrderBy("ID").ToString());

    [Fact]
    public void EncodesEachValueOnce()
    {
        var encoded = ODataQuery.None().Filter(ODataProperty.Of("Name").Eq("a&b=c+d #e%f")).Option("!sandbox", "What if & more").ToQueryString();
        Assert.DoesNotContain(" ", encoded);
        Assert.DoesNotContain("+", encoded);
        foreach (var escape in new[] { "%26", "%3D", "%2B", "%23", "%25" })
        {
            Assert.Contains(escape, encoded);
        }
        var back = Parse(encoded);
        Assert.Equal("(Name eq 'a&b=c+d #e%f')", back["$filter"]);
        Assert.Equal("What if & more", back["!sandbox"]);
    }

    [Fact]
    public void SurvivesFuzzedFilterValues()
    {
        var random = new Random(42);
        var alphabet = Runes("aZ0 '\"/%{}&=+#?!$@:;,()*É日😀");
        for (int i = 0; i < 3000; i++)
        {
            var v = string.Concat(Enumerable.Range(0, 1 + random.Next(10)).Select(_ => alphabet[random.Next(alphabet.Length)]));
            var encoded = ODataQuery.None().Filter(ODataProperty.Of("Name").Eq(v)).Option("!sandbox", v).ToQueryString();
            Assert.True(Uri.TryCreate("http://tm1/api/v1/Cubes?" + encoded, UriKind.Absolute, out _), v);
            var back = Parse(encoded);
            Assert.Equal("(Name eq " + ODataLiterals.String(v) + ")", back["$filter"]);
            Assert.Equal(v, back["!sandbox"]);
        }
    }

    [Fact]
    public void DefinesParameterAliases()
    {
        var query = ODataQuery.None().Alias(new ODataPath.Alias("@tuple"), "[\"a\",\"b\"]");
        Assert.Equal("@tuple=[\"a\",\"b\"]", query.ToString());
        Assert.Equal("[\"a\",\"b\"]", Parse(query.ToQueryString())["@tuple"]);
    }

    [Fact]
    public void RefusesBadOptions()
    {
        Assert.Throws<ArgumentException>(() => ODataQuery.None().Option("$top", "1"));
        Assert.Throws<ArgumentException>(() => ODataQuery.None().Option("@p", "1"));
        Assert.Throws<ArgumentException>(() => ODataQuery.None().Top(-1));
        Assert.Throws<ArgumentException>(() => new ODataPath.Alias("p"));
    }

    [Fact]
    public void IsImmutable()
    {
        var @base = ODataQuery.None().Select("Name");
        var more = @base.Top(1);
        Assert.Equal("$select=Name", @base.ToString());
        Assert.Equal("$select=Name&$top=1", more.ToString());
        Assert.Null(@base.Option("!sandbox"));
    }

    [Fact]
    public void TakesPropertiesForExpandAndOrderBy()
    {
        var dimensions = ODataProperty.Of("Dimensions");
        var name = ODataProperty.Of("Name");
        Assert.Equal(
            ODataQuery.None().Expand("Dimensions", d => d.Select("Name")).Expand("Views").OrderBy("Name").OrderByDescending("LastDataUpdate"),
            ODataQuery
                .None()
                .Expand(dimensions, d => d.Select(name))
                .Expand(ODataProperty.Of("Views"))
                .OrderBy(name)
                .OrderByDescending(ODataProperty.Of("LastDataUpdate"))
        );
    }

    /// <summary>Splits and decodes a query string once, as a server would.</summary>
    internal static Dictionary<string, string> Parse(string query) =>
        query.Split('&').ToDictionary(p => Uri.UnescapeDataString(p[..p.IndexOf('=')]), p => Uri.UnescapeDataString(p[(p.IndexOf('=') + 1)..]));

    internal static string[] Runes(string alphabet) => alphabet.EnumerateRunes().Select(r => r.ToString()).ToArray();
}

/// <summary>The Java client's <c>FilterTest</c>.</summary>
public class FilterTest
{
    private static readonly ODataProperty Name = ODataProperty.Of("Name");
    private static readonly ODataProperty Level = ODataProperty.Of("Level");

    [Fact]
    public void ComparesWithLiterals()
    {
        Assert.Equal("(Name eq 'O''Neil')", Name.Eq("O'Neil").ToString());
        Assert.Equal("(Level ge 0)", Level.Ge(0).ToString());
        Assert.Equal("(Weight lt 1.50)", ODataProperty.Of("Weight").Lt(1.50m).ToString());
        Assert.Equal("(Value gt 2.5)", ODataProperty.Of("Value").Gt(2.5d).ToString());
        Assert.Equal("(Active eq true)", ODataProperty.Of("Active").Eq(true).ToString());
        Assert.Equal("(Parent eq null)", ODataProperty.Of("Parent").IsNull().ToString());
        Assert.Equal(
            "(Type eq ibm.tm1.api.v1.ElementType'Numeric')",
            ODataProperty.Of("Type").Eq(new ODataLiterals.Raw(ODataLiterals.EnumMember("ibm.tm1.api.v1.ElementType", "Numeric"))).ToString()
        );
    }

    [Fact]
    public void ParenthesizesEveryCombination()
    {
        var f = Filter.Or(Filter.And(Name.Eq("a"), Level.Eq(0)), Filter.Not(Filter.StartsWith(Name, "}")));
        Assert.Equal("(((Name eq 'a') and (Level eq 0)) or not(startswith(Name,'}')))", f.ToString());
        Assert.Equal(Name.Eq("a"), Filter.And(Name.Eq("a")));
    }

    [Fact]
    public void UsesCanonicalFunctions()
    {
        Assert.Equal("contains(Name,'Q1')", Filter.Contains(Name, "Q1").ToString());
        Assert.Equal("endswith(Name,'''s')", Filter.EndsWith(Name, "'s").ToString());
        Assert.Equal("(Name in ('a','b'))", Filter.In(Name, "a", "b").ToString());
    }

    [Fact]
    public void WritesLambdas()
    {
        Assert.Equal("Hierarchies/any(h:(h/Name eq '}Clients'))", Filter.Any("Hierarchies", "h", h => h.Nav("Name").Eq("}Clients")).ToString());
        Assert.Equal("Tags/all(t:(t ne 'x'))", Filter.All("Tags", "t", t => t.Self().Ne("x")).ToString());
    }

    [Fact]
    public void RefusesValuesWithoutAClearLiteral()
    {
        Assert.Throws<ArgumentException>(() => Name.Eq(new object()));
        Assert.Throws<ArgumentException>(() => Filter.In(Name));
        Assert.Throws<ArgumentException>(() => Filter.And());
        Assert.Throws<ArgumentException>(() => Filter.Any("X", "1v", v => v.Self().Eq(1)));
    }

    [Fact]
    public void WritesSpecialNumbers()
    {
        Assert.Equal("NaN", ODataLiterals.Of(double.NaN));
        Assert.Equal("-INF", ODataLiterals.Of(double.NegativeInfinity));
        Assert.Equal("0.0000001", ODataLiterals.Of(1e-7));
        Assert.Equal("12", ODataLiterals.Of(12L));
    }

    [Fact]
    public void WritesAValueThatKnowsItsOwnLiteral() =>
        Assert.Equal("(Type eq ibm.tm1.api.v1.ElementType'Numeric')", ODataProperty.Of("Type").Eq(new Numeric()).ToString());

    private sealed class Numeric : IODataLiteral
    {
        public string ODataLiteral() => "ibm.tm1.api.v1.ElementType'Numeric'";
    }
}

/// <summary>The Java client's <c>ODataPathTest</c>.</summary>
public class ODataPathTest
{
    [Fact]
    public void AddressesTheMetadataDocument()
    {
        Assert.Equal("$metadata", ODataPath.Metadata().ToString());
        Assert.Throws<InvalidOperationException>(() => ODataPath.Metadata().Key("x"));
    }

    [Fact]
    public void BuildsNestedPaths()
    {
        Assert.Equal(
            "Dimensions('Region')/Hierarchies('Region')/Elements",
            ODataPath.Of("Dimensions").Key("Region").Nav("Hierarchies").Key("Region").Nav("Elements").ToString()
        );
        Assert.Equal("Cubes('Sales')/tm1.Reserve", ODataPath.Of("Cubes").Key("Sales").Action("tm1.Reserve").ToString());
        Assert.Equal("Cubes('Sales')/Views/tm1.NativeView", ODataPath.Of("Cubes").Key("Sales").Nav("Views").Cast("tm1.NativeView").ToString());
        Assert.Equal("Configuration/ProductVersion/$value", ODataPath.Of("Configuration").Nav("ProductVersion").Value().ToString());
        Assert.Equal("Dimensions/$count", ODataPath.Of("Dimensions").Count().ToString());
        Assert.Equal("Cubes('Sales')/Dimensions/$ref", ODataPath.Of("Cubes").Key("Sales").Nav("Dimensions").Ref().ToString());
        Assert.Equal("Threads(42)", ODataPath.Of("Threads").Key(42).ToString());
    }

    [Fact]
    public void WritesCompoundKeysInOrder() =>
        Assert.Equal(
            "Edges(ParentName='Total',ComponentName='Q1%20''26')",
            ODataPath.Of("Edges").Key(new Dictionary<string, object?> { ["ParentName"] = "Total", ["ComponentName"] = "Q1 '26" }).ToString()
        );

    [Fact]
    public void CallsFunctionsWithLiteralsOrAliases()
    {
        Assert.Equal(
            "Cellsets('abc')/tm1.GetPartition(Begin=0,End=99)",
            ODataPath.Of("Cellsets").Key("abc").Function("tm1.GetPartition", new Dictionary<string, object?> { ["Begin"] = 0, ["End"] = 99 }).ToString()
        );
        Assert.Equal(
            "Hierarchies/tm1.Fn(p=@p)",
            ODataPath.Of("Hierarchies").Function("tm1.Fn", new Dictionary<string, object?> { ["p"] = new ODataPath.Alias("@p") }).ToString()
        );
    }

    [Theory]
    [InlineData("}Clients")]
    [InlineData("Year 2026")]
    [InlineData("O'Neil")]
    [InlineData("a/b")]
    [InlineData("50%")]
    [InlineData("{x}")]
    [InlineData("a&b=c+d#e")]
    [InlineData("Région")]
    [InlineData("日本")]
    [InlineData("😀")]
    [InlineData("''")]
    [InlineData("%2F")]
    [InlineData("?")]
    [InlineData("\\")]
    [InlineData("a\tb")]
    public void KeepsAwkwardNamesIntact(string name) => Assert.Equal(name, KeyOf(ODataPath.Of("Dimensions").Key(name).Nav("Hierarchies")));

    [Fact]
    public void EncodesSlashesSoTheyCantSplitThePath()
    {
        Assert.Equal("Dimensions('a%2Fb')", ODataPath.Of("Dimensions").Key("a/b").ToString());
        Assert.Equal("Dimensions('O''Neil')", ODataPath.Of("Dimensions").Key("O'Neil").ToString());
        Assert.Equal("Dimensions('%7DClients')", ODataPath.Of("Dimensions").Key("}Clients").ToString());
        Assert.Equal("Dimensions('a%20b')", ODataPath.Of("Dimensions").Key("a b").ToString());
    }

    [Fact]
    public void SurvivesFuzzedNames()
    {
        var random = new Random(20261121);
        var alphabet = ODataQueryTest.Runes("aZ09 '\"/\\%{}[]()&=+#?!$@:;,.*~_-^|<>`\tÉé日😀");
        for (int i = 0; i < 5000; i++)
        {
            var name = string.Concat(Enumerable.Range(0, 1 + random.Next(12)).Select(_ => alphabet[random.Next(alphabet.Length)]));
            var path = ODataPath.Of("Cubes").Key(name).Nav("Views").Key(name);
            Assert.True(Uri.TryCreate("http://tm1:8010/api/v1/" + path, UriKind.Absolute, out _), name);
            Assert.Equal(name, KeyOf(ODataPath.Of("Cubes").Key(name)));
        }
    }

    [Theory]
    [InlineData("")]
    [InlineData("Cubes('x')")]
    [InlineData("a/b")]
    [InlineData("1abc")]
    [InlineData("$metadata")]
    [InlineData("Cubes Sales")]
    public void RefusesNamesThatArentIdentifiers(string name)
    {
        Assert.Throws<ArgumentException>(() => ODataPath.Of(name));
        Assert.Throws<ArgumentException>(() => ODataPath.Of("Cubes").Nav(name));
    }

    [Fact]
    public void RefusesTwoKeysOnOneSegment()
    {
        Assert.Throws<InvalidOperationException>(() => ODataPath.Of("Cubes").Key("a").Key("b"));
        Assert.Throws<InvalidOperationException>(() => ODataPath.Of("Cubes").Count().Key("a"));
    }

    [Fact]
    public void IsAValueType()
    {
        Assert.Equal(ODataPath.Of("Cubes").Key("a"), ODataPath.Of("Cubes").Key("a"));
        Assert.Equal(ODataPath.Of("Cubes").Key("a").GetHashCode(), ODataPath.Of("Cubes").Key("a").GetHashCode());
    }

    [Fact]
    public void CallsAFunctionImportFromTheServiceRoot()
    {
        Assert.Equal("ModelCubes()", ODataPath.FunctionImport("ModelCubes", new Dictionary<string, object?>()).ToString());
        Assert.Equal("TailMessageLog(Top=10)", ODataPath.FunctionImport("TailMessageLog", new Dictionary<string, object?> { ["Top"] = 10 }).ToString());
    }

    [Fact]
    public void FollowsAServerLinkEncodingEachSegment()
    {
        Assert.Equal("MessageLogEntries/!delta('0%5B4096%5D38%4011!19')", ODataPath.Link("MessageLogEntries/!delta('0[4096]38@11!19')").ToString());
        Assert.Throws<ArgumentException>(() => ODataPath.Link("Cubes?$top=1"));
        Assert.Throws<ArgumentException>(() => ODataPath.Link("/api/v1/Cubes"));
    }

    /// <summary>Decodes a path once, as a server would, and reads back the first segment's key literal.</summary>
    private static string KeyOf(ODataPath path)
    {
        var segment = Uri.UnescapeDataString(path.ToString().Split('/')[0]);
        var literal = segment[(segment.IndexOf('(') + 1)..segment.LastIndexOf(')')];
        Assert.StartsWith("'", literal);
        Assert.EndsWith("'", literal);
        return literal[1..^1].Replace("''", "'", StringComparison.Ordinal);
    }
}

/// <summary>The Java client's <c>Tm1VersionTest</c>.</summary>
public class Tm1VersionTest
{
    [Fact]
    public void KeepsTheTextAsReported() => Assert.Equal("11.8.03500.6", Tm1Version.Parse(" 11.8.03500.6\n").ToString());

    [Fact]
    public void ComparesPartsNumerically()
    {
        Assert.Equal(Tm1Version.Parse("11.8.3500.6"), Tm1Version.Parse("11.8.03500.6"));
        Assert.True(Tm1Version.Parse("11.8.03500.6") > Tm1Version.Parse("11.8.02800.9"));
        Assert.True(Tm1Version.Parse("11.10") > Tm1Version.Parse("11.9.99999"));
        Assert.True(Tm1Version.Parse("12.0.0") > Tm1Version.Parse("11.8.03500.6"));
    }

    [Fact]
    public void TreatsMissingPartsAsZero()
    {
        Assert.Equal(Tm1Version.Parse("11.8.0.0"), Tm1Version.Parse("11.8"));
        Assert.Equal(Tm1Version.Parse("11.8.0.0").GetHashCode(), Tm1Version.Parse("11.8").GetHashCode());
        Assert.True(Tm1Version.Parse("11.8.1") > Tm1Version.Parse("11.8"));
    }

    /// <summary>.NET only: == agrees with Equals and the other operators, rather than comparing references.</summary>
    [Fact]
    public void ComparesWithTheEqualityOperatorsAsEqualsDoes()
    {
        Assert.True(Tm1Version.Parse("11.8.0.0") == Tm1Version.Parse("11.8"));
        Assert.False(Tm1Version.Parse("11.8.0.0") != Tm1Version.Parse("11.8"));
        Assert.True(Tm1Version.Parse("11.8.1") != Tm1Version.Parse("11.8"));
        Tm1Version? none = null;
        Assert.True(none == null);
        Assert.True(Tm1Version.Parse("11.8") != none);
    }

    /// <summary>.NET only: a $metadata that leaves out an attribute CSDL requires is refused as one the client can't read.</summary>
    [Fact]
    public void RefusesMetadataWithoutARequiredAttribute()
    {
        const string csdl =
            "<edmx:Edmx xmlns:edmx=\"http://docs.oasis-open.org/odata/ns/edmx\" Version=\"4.0\"><edmx:DataServices>"
            + "<Schema xmlns=\"http://docs.oasis-open.org/odata/ns/edm\" Namespace=\"ibm.tm1.api.v1\"><EntityType/></Schema>"
            + "</edmx:DataServices></edmx:Edmx>";
        Assert.Contains("EntityType has no Name", Assert.Throws<Tm1ProtocolException>(() => Tm1Capabilities.Parse(csdl)).Message);
    }

    [Fact]
    public void AnswersRangeQuestions()
    {
        var v = Tm1Version.Parse("11.8.03500.6");
        Assert.True(v.IsAtLeast(Tm1Version.Parse("11.8.03500.6")));
        Assert.True(v.IsAtLeast(Tm1Version.Parse("11.8.02300")));
        Assert.False(v.IsAtLeast(Tm1Version.Parse("11.8.03600")));
        Assert.True(v.IsBefore(Tm1Version.Parse("12")));
        Assert.False(v.IsBefore(v));
    }

    [Theory]
    [InlineData("")]
    [InlineData("11.")]
    [InlineData(".8")]
    [InlineData("11..8")]
    [InlineData("v11.8")]
    [InlineData("11.8-beta")]
    [InlineData("99999999999")]
    public void RejectsNonVersions(string text) => Assert.Contains("Not a TM1 version", Assert.Throws<ArgumentException>(() => Tm1Version.Parse(text)).Message);
}

/// <summary>The Java client's <c>Tm1DialectTest</c>.</summary>
public class Tm1DialectTest
{
    [Fact]
    public void FindsTheTm111ServiceRootFromAnAddress()
    {
        Assert.Equal("http://tm1:8010/api/v1/", Tm1Dialect.V11.ServiceRoot("http://tm1:8010"));
        Assert.Equal("https://tm1.example.com:12354/api/v1/", Tm1Dialect.V11.ServiceRoot("https://tm1.example.com:12354/"));
        Assert.Equal("http://tm1:8010/api/v1/", Tm1Dialect.V11.ServiceRoot("http://tm1:8010/api/v1")); // already a root
        Assert.Equal("http://proxy/tm1/sales/api/v1/", Tm1Dialect.V11.ServiceRoot("http://proxy/tm1/sales/")); // behind a proxy path
    }

    [Fact]
    public void RefusesWhatIsntAnAddress()
    {
        Assert.Throws<ArgumentException>(() => Tm1Dialect.V11.ServiceRoot("tm1:8010/x"));
        Assert.Throws<ArgumentException>(() => Tm1Dialect.V11.ServiceRoot("/api/v1/"));
        Assert.Throws<ArgumentException>(() => Tm1Dialect.V11.ServiceRoot("http://tm1:8010?x=1"));
    }

    [Fact]
    public void LetsASessionBeBuiltFromAnAddress() =>
        Assert.Equal("http://tm1:8010/api/v1/", Tm1Session.Builder().Address("http://tm1:8010").BasicAuth("admin", "").Build().ServiceRoot());
}

/// <summary>The Java client's <c>Tm1RequestTest</c>.</summary>
public class Tm1RequestTest
{
    [Fact]
    public void DescribesAGet()
    {
        var get = Tm1Request.Get<JsonElement>(ODataPath.Of("Cubes").Key("Sales"), ODataQuery.None().Select("Name"));
        Assert.Equal("GET", get.Method);
        Assert.Equal("Cubes('Sales')?$select=Name", get.RelativeUri());
        Assert.Equal(Tm1Response.Single, get.Response);
        Assert.True(get.IsIdempotent());
        Assert.Equal("GET Cubes('Sales')?$select=Name", get.ToString());
    }

    [Fact]
    public void GivesActionsAJsonBodyEvenWithoutParameters()
    {
        var reserve = Tm1Request.Post(ODataPath.Of("Cubes").Key("Sales").Action("tm1.Reserve"), null);
        Assert.Empty(Assert.IsAssignableFrom<IDictionary<string, object?>>(reserve.Body));
        Assert.Equal(Tm1Response.None, reserve.Response);
        Assert.False(reserve.IsIdempotent());
    }

    [Fact]
    public void DescribesOtherKinds()
    {
        Assert.Equal(Tm1Response.Collection, Tm1Request.List<JsonElement>(ODataPath.Of("Cubes")).Response);
        Assert.Equal(Tm1Response.Text, Tm1Request.Text(ODataPath.Of("Dimensions").Count()).Response);
        Assert.False(Tm1Request.Patch(ODataPath.Of("Cubes").Key("x"), new Dictionary<string, object?> { ["Rules"] = "" }).IsIdempotent());
        Assert.True(Tm1Request.Delete(ODataPath.Of("Cubes").Key("x")).IsIdempotent());
    }

    [Fact]
    public void IsImmutable()
    {
        var delete = Tm1Request.Delete(ODataPath.Of("Cubes").Key("x"));
        var guarded = delete.IfMatch("W/\"1\"").WithQuery(ODataQuery.None().Option("!sandbox", "s"));
        Assert.Empty(delete.Headers);
        Assert.Equal("W/\"1\"", guarded.Headers["If-Match"]);
        Assert.Equal("Cubes('x')?!sandbox=s", guarded.RelativeUri());
    }

    [Fact]
    public void CarriesItsOwnTimeoutThroughCopies()
    {
        var run = Tm1Request.Post(ODataPath.Of("Processes").Key("p").Action("tm1.ExecuteWithReturn"), null);
        Assert.Null(run.Timeout);
        var slow = run.WithTimeout(TimeSpan.FromHours(2)).WithHeader("Prefer", "respond-async");
        Assert.Equal(TimeSpan.FromHours(2), slow.Timeout);
        Assert.Null(run.Timeout);
        Assert.Throws<ArgumentException>(() => run.WithTimeout(TimeSpan.Zero));
    }

    [Fact]
    public void PostsAndStreamsACollection()
    {
        var request = Tm1Request.PostList<string>(ODataPath.Of("Cubes").Key("c").Action("tm1.CheckRules"), new Dictionary<string, object?>());
        Assert.Equal("POST", request.Method);
        Assert.Equal(Tm1Response.Collection, request.Response);
        Assert.False(request.IsIdempotent());
    }

    [Fact]
    public void DescribesRawBytesBothWays()
    {
        var download = Tm1Request.Bytes(ODataPath.Of("Contents").Key("Blobs").Nav("Contents").Key("a.txt"));
        Assert.Equal(Tm1Response.Bytes, download.Response);
        Assert.Equal("GET", download.Method);
        byte[] content = [1, 2];
        var upload = Tm1Request.Put(ODataPath.Of("Contents").Key("Blobs"), content);
        Assert.Equal("PUT", upload.Method);
        Assert.True(upload.IsIdempotent());
        Assert.Same(content, upload.Body);
        Assert.Equal(Tm1Response.None, upload.Response);
    }
}

/// <summary>The Java client's <c>Tm1OperationTest</c>.</summary>
public class Tm1OperationTest
{
    [Fact]
    public void ABoundOperationNeedsItsBindingAndOperation()
    {
        var activate = new Tm1Operation(Tm1Operation.OperationKind.Action, "tm1.Activate", "tm1.Chore", null, [], null, "10.2.2.1", "11.8.3");
        Assert.True(activate.Bound());
        Assert.True(activate.Deprecated());
        Assert.Equal("Action Chore/Activate", activate.Endpoint());
        Assert.Equal(Tm1Capability.BoundOperation("tm1.Chore", "tm1.Activate").Since("10.2.2.1"), activate.Capability());
    }

    [Fact]
    public void AnUnboundOperationNeedsItsImport()
    {
        var mdx = new Tm1Operation(
            Tm1Operation.OperationKind.Action,
            "tm1.ExecuteMDX",
            null,
            "ExecuteMDX",
            [new Tm1Operation.Parameter("MDX", "Edm.String", false)],
            "tm1.Cellset",
            null,
            null
        );
        Assert.False(mdx.Bound());
        Assert.Equal("ActionImport ExecuteMDX", mdx.Endpoint());
        Assert.Equal(Tm1Capability.UnboundOperation("ExecuteMDX"), mdx.Capability());
    }

    [Fact]
    public void NeedsExactlyOneOfABindingAndAnImport()
    {
        Assert.Throws<ArgumentException>(() => new Tm1Operation(Tm1Operation.OperationKind.Function, "tm1.F", null, null, [], null, null, null));
        Assert.Throws<ArgumentException>(() => new Tm1Operation(Tm1Operation.OperationKind.Function, "tm1.F", "tm1.Cube", "F", [], null, null, null));
    }
}
