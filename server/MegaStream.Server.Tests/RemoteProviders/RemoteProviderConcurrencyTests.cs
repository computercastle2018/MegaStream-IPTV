using System.Threading.Channels;
using MegaStream.Server.RemoteProviders.Core;
using Microsoft.EntityFrameworkCore;
using Xunit;
using Session = MegaStream.Server.Tests.RemoteProviders.RemoteProviderServiceTests.Session;

namespace MegaStream.Server.Tests.RemoteProviders;

public sealed class RemoteProviderConcurrencyTests
{
    [Fact]
    public async Task Simultaneous_independent_context_commits_have_unique_ordered_revisions_and_no_delta_misses()
    {
        await using var store = await RemoteProviderDatabase.CreateAsync();
        var installation = await store.AddInstallationAsync();
        const int contenders = 6;
        var profileIds = new List<Guid>();
        long cursor;
        await using (var setup = new Session(store))
        {
            for (var i = 0; i < contenders; i++)
                profileIds.Add((await setup.Service.CreateProfileAsync(RemoteProviderTestData.Profile())).Id);
            cursor = (await setup.Service.GetDeviceAsync(installation)).Revision;
        }
        var initialCursor = cursor;
        using var start = new Barrier(contenders);
        var committed = Channel.CreateUnbounded<RemoteProviderAssignment>();
        var writes = profileIds.Select(id => Task.Run(async () =>
        {
            await using var writer = new Session(store);
            if (!start.SignalAndWait(TimeSpan.FromSeconds(15))) throw new TimeoutException("Revision contenders did not start.");
            var assignment = await writer.Service.AssignAsync(id, installation, new());
            await committed.Writer.WriteAsync(assignment);
            return assignment;
        })).ToArray();
        var completion = CompleteChannelAsync();
        var seen = new Dictionary<Guid, long>();
        await foreach (var acknowledged in committed.Reader.ReadAllAsync())
        {
            await using var reader = new Session(store);
            var delta = await reader.Service.GetDeviceAsync(installation, cursor);
            Assert.True(delta.Revision >= cursor);
            var revisions = delta.Items.Select(x => x.Revision).ToArray();
            Assert.Equal(revisions.Order().ToArray(), revisions);
            foreach (var item in delta.Items)
            {
                Assert.True(item.Revision > cursor);
                Assert.True(item.Revision <= delta.Revision);
                Assert.True(seen.TryAdd(item.AssignmentId, item.Revision), "A cursor replayed a previously acknowledged assignment.");
            }
            cursor = delta.Revision;
            Assert.Contains(acknowledged.Id, seen.Keys);
        }
        await completion;
        var assignments = await Task.WhenAll(writes);
        Assert.Equal(contenders, assignments.Select(x => x.Revision).Distinct().Count());
        Assert.Equal(assignments.Select(x => x.Id).Order().ToArray(), seen.Keys.Order().ToArray());
        Assert.All(assignments, assignment => Assert.True(assignment.Revision > initialCursor));
        await using var final = new Session(store);
        var replay = await final.Service.GetDeviceAsync(installation, initialCursor);
        Assert.Equal(assignments.Select(x => x.Id).Order().ToArray(), replay.Items.Select(x => x.AssignmentId).Order().ToArray());
        Assert.Empty((await final.Service.GetDeviceAsync(installation, cursor)).Items);
        Assert.Equal(contenders, await final.Db.Set<RemoteProviderAssignment>().CountAsync());

        async Task CompleteChannelAsync()
        {
            try
            {
                await Task.WhenAll(writes);
                committed.Writer.TryComplete();
            }
            catch (Exception error)
            {
                committed.Writer.TryComplete(error);
                throw;
            }
        }
    }
}
