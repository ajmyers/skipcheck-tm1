using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace Skipcheck.Tm1.Client.Errors;

/// <summary>What a refusal means, from TM1's status, code and message, as the Java client's
/// <c>Tm1ErrorClassifier</c>: the first rule that matches, else by status.</summary>
public sealed class Tm1ErrorClassifier
{
    private static readonly Tm1ErrorClassifier DefaultRules = new([
        Rule.Message("insufficient rights to impersonate", Tm1ErrorKind.ImpersonationRefused),
        Rule.Message("administrator to be able to impersonate", Tm1ErrorKind.ImpersonationRefused),
        Rule.Message("user specified in the impersonation request does not exist", Tm1ErrorKind.UnknownUser),
        Rule.Message("tm1userexception: cancel", Tm1ErrorKind.Cancelled),
        Rule.Message("DataSpreadFailed", Tm1ErrorKind.DataSpreadFailed),
        Rule.Code("248", Tm1ErrorKind.InvalidUniqueName),
        Rule.Code("65", Tm1ErrorKind.NoReadRights),
        Rule.Message("ObjectSecurityNoReadRights", Tm1ErrorKind.NoReadRights),
        Rule.Message("can ?not be found|not found|does not exist|doesn't exist|ObjectNotFound", Tm1ErrorKind.NotFound),
        Rule.Message("already exists", Tm1ErrorKind.AlreadyExists),
        Rule.Message(@"\blocked\b", Tm1ErrorKind.Locked),
        Rule.Message(@"\bin use\b|is used by|isbeingusedby|cannot be deleted", Tm1ErrorKind.InUse),
    ]);

    private readonly IReadOnlyList<Rule> _rules;

    private Tm1ErrorClassifier(IReadOnlyList<Rule> rules) => _rules = rules;

    /// <summary>The rules this client knows about.</summary>
    public static Tm1ErrorClassifier Defaults() => DefaultRules;

    /// <summary>This classifier with <paramref name="rule"/> tried first.</summary>
    public Tm1ErrorClassifier WithRule(Rule rule) => new([rule ?? throw new ArgumentNullException(nameof(rule)), .. _rules]);

    /// <summary>Classifies an error response: the first matching rule's kind, or else the kind for
    /// the status (<see cref="Tm1ErrorKind.Other"/> if it has none).</summary>
    /// <param name="status">The HTTP status.</param>
    /// <param name="error">The parsed error body, or null if the body wasn't an OData
    /// error.</param>
    public Tm1ErrorKind Classify(int status, ODataError? error)
    {
        foreach (var rule in _rules)
        {
            if (rule.Matches(status, error))
            {
                return rule.Kind;
            }
        }
        return status switch
        {
            401 => Tm1ErrorKind.Unauthenticated,
            403 => Tm1ErrorKind.Forbidden,
            404 => Tm1ErrorKind.NotFound,
            409 => Tm1ErrorKind.Conflict,
            412 or 428 => Tm1ErrorKind.PreconditionFailed,
            501 => Tm1ErrorKind.NotImplemented,
            _ => Tm1ErrorKind.Other,
        };
    }

    /// <summary>A rule: the status, code and message to match (any null matches anything; at least
    /// one is needed), and the kind it means.</summary>
    public sealed record Rule
    {
        /// <summary>A rule. Each condition that's set must match: the status, the code exactly, and
        /// the message pattern anywhere in the message.</summary>
        /// <param name="status">The HTTP status to match, or null for any.</param>
        /// <param name="code">The error code to match, or null for any.</param>
        /// <param name="message">A pattern to find in the message, or null for any.</param>
        /// <param name="kind">The kind to report on a match.</param>
        /// <exception cref="ArgumentException">No condition is set.</exception>
        public Rule(int? status, string? code, Regex? message, Tm1ErrorKind kind)
        {
            if (status is null && code is null && message is null)
            {
                throw new ArgumentException("A rule needs at least one condition");
            }
            Status = status;
            CodeValue = code;
            MessagePattern = message;
            Kind = kind;
        }

        /// <summary>The HTTP status to match, or null for any.</summary>
        public int? Status { get; }

        /// <summary>The error code to match exactly, or null for any.</summary>
        public string? CodeValue { get; }

        /// <summary>The pattern to find in the message, or null for any.</summary>
        public Regex? MessagePattern { get; }

        /// <summary>The kind to report on a match.</summary>
        public Tm1ErrorKind Kind { get; }

        /// <summary>Matches TM1's message, case-insensitively.</summary>
        public static Rule Message(string regex, Tm1ErrorKind kind) => new(null, null, new Regex(regex, RegexOptions.IgnoreCase), kind);

        /// <summary>Matches TM1's error code.</summary>
        public static Rule Code(string code, Tm1ErrorKind kind) => new(null, code, null, kind);

        internal bool Matches(int status, ODataError? error) =>
            (Status is null || Status == status)
            && (CodeValue is null || (error is not null && error.Code == CodeValue))
            && (MessagePattern is null || (error is not null && MessagePattern.IsMatch(error.Message)));
    }
}
