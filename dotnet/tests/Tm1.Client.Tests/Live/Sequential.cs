using Xunit;

// The live tests share one TM1, as Java's do, and Java's run one class at a time. Several count
// the server's sessions or read its logs, so another class's logins at the same moment fail them.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
