using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Skipcheck.Tm1.Client.Errors;
using Skipcheck.Tm1.Client.Model;
using Skipcheck.Tm1.Client.OData;
using ServerThread = Skipcheck.Tm1.Client.Model.Thread;

namespace Skipcheck.Tm1.Client.Tests.Live;

/// <summary>UserService and GroupService against a real TM1, on a user and two groups of their own:
/// Java's <c>UserAndGroupTm1Test</c>.</summary>
public sealed class UserAndGroupLiveTests : IAsyncLifetime
{
    private Tm1Client _tm1 = null!;
    private readonly string _user;
    private readonly string _g1;
    private readonly string _g2;

    public UserAndGroupLiveTests()
    {
        var id = Guid.NewGuid().ToString("N")[..8];
        (_user, _g1, _g2) = ("tm1net_test_" + id, "tm1net_test_g1_" + id, "tm1net_test_g2_" + id);
    }

    public async Task InitializeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        _tm1 = Live.Client();
        await _tm1.Groups.CreateAsync(_g1);
        await _tm1.Groups.CreateAsync(_g2);
        await _tm1.Users.CreateAsync(UserPatch.Builder().Name(_user).Password("pw").BindGroups(ODataPath.Of("Groups").Key(_g1)).Build());
    }

    public async Task DisposeAsync()
    {
        if (!Live.Configured)
        {
            return;
        }
        foreach (var delete in new Func<Task>[] { () => _tm1.Users.DeleteAsync(_user), () => _tm1.Groups.DeleteAsync(_g1), () => _tm1.Groups.DeleteAsync(_g2) })
        {
            try
            {
                await delete();
            }
            catch (Tm1Exception)
            {
                // Already gone
            }
        }
        await _tm1.DisposeAsync();
    }

    [Tm1Fact]
    public async Task CreatesReadsUpdatesAndDeletesAUser()
    {
        var read = await _tm1.Users.GetAsync(_user);
        Assert.Equal(UserType.User, read.Type);
        // Names match ignoring case and spaces
        await _tm1.Users.GetAsync(_user.ToUpperInvariant().Replace("_", " _ ", StringComparison.Ordinal));
        Assert.Null(read.ETag); // TM1 sends none for a user
        await _tm1.Users.UpdateAsync(_user, UserPatch.Builder().FriendlyName("Friend").Build());
        Assert.Equal("Friend", (await _tm1.Users.GetAsync(_user)).FriendlyName);
        Assert.Equal(Live.Target().User, (await _tm1.Users.ActiveUserAsync()).Name, ignoreCase: true);
        await _tm1.Users.DeleteAsync(_user);
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.Users.GetAsync(_user));
    }

    [Tm1Fact]
    public async Task SetsGroupsWholeAndListsMembersFromBothSides()
    {
        Assert.Equal([_g1], await Groups());
        await _tm1.Users.SetGroupsAsync(_user, [_g1, _g2]);
        Assert.Equal(new[] { _g1, _g2 }.Order(), (await Groups()).Order());
        await _tm1.Users.SetGroupsAsync(_user, [_g2]);
        Assert.Equal([_g2], await Groups()); // replaced
        Assert.Equal([_user], await _tm1.Groups.UsersAsync(_g2).Select(u => u.Name).ToListAsync());
        Assert.Equal(_g1, (await _tm1.Groups.GetAsync(_g1)).Name); // left, not deleted
        var e = await Assert.ThrowsAsync<ArgumentException>(() =>
            _tm1.Users.UpdateAsync(_user, UserPatch.Builder().BindGroups(ODataPath.Of("Groups").Key(_g1)).Build())
        );
        Assert.Contains("SetGroups", e.Message, StringComparison.Ordinal);
    }

    [Tm1Fact]
    public async Task DeletesTheGroupItselfOnAReferenceDelete()
    {
        // The call UserService never makes: OData's "remove the link" deletes the group
        await _tm1.Session.ExecuteAsync(Tm1Request.Delete(ODataPath.Of("Users").Key(_user).Nav("Groups").Key(_g1).Ref()));
        await Assert.ThrowsAsync<Tm1NotFoundException>(() => _tm1.Groups.GetAsync(_g1));
    }

    [Tm1Fact]
    public async Task DisconnectsAUser()
    {
        await using var asUser = OtherClient.Build(user: _user, password: "pw");
        await asUser.ConnectAsync();
        Assert.True((await _tm1.Users.GetAsync(_user)).IsActive);
        await _tm1.Users.DisconnectAsync(_user);
        Assert.False((await _tm1.Users.GetAsync(_user)).IsActive);
    }

    private async Task<List<string?>> Groups() => await _tm1.Users.GroupsAsync(_user).Select(g => g.Name).ToListAsync();
}
