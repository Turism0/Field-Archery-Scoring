using FieldArcheryApi.Rounds;

namespace FieldArcheryApi.Groups;

/// <summary>Scoring methods, member kinds and the rules for who scores which card. The app has the same rules.</summary>
public static class GroupRules
{
    public const string Single = "SINGLE";   // one scorer, others watching
    public const string Double = "DOUBLE";   // two scorers enter every score
    public const string Multi = "MULTI";     // 3+ scorers, nobody scores their own card, two per card
    public const string Self = "SELF";       // everyone scores their own

    public static readonly HashSet<string> Methods = [Single, Double, Multi, Self];

    public const string Host = "HOST";
    public const string Joined = "JOINED";
    public const string Friend = "FRIEND";
    public const string Guest = "GUEST";

    public const string Open = "OPEN";
    public const string Started = "STARTED";
    public const string Closed = "CLOSED";

    /// <summary>Join codes: 6 characters, no 0/O, 1/I/L (easy to read out).</summary>
    public const string CodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static readonly TimeSpan CodeLifetime = TimeSpan.FromHours(24);
    public static readonly TimeSpan GroupLifetime = TimeSpan.FromDays(30);

    public static bool IsCode(string? code) => code is { Length: 6 } && code.All(CodeAlphabet.Contains);

    /// <summary>A member who has the group on their own phone (and so can score).</summary>
    public static bool HasPhone(string kind) => kind is Host or Joined;

    public sealed record Member(Guid MemberId, string Kind, int SortOrder);

    public sealed record Scorer(Guid CardMemberId, Guid ScorerMemberId);

    /// <summary>
    /// The usual scorers for a method. SINGLE: the host scores everyone. DOUBLE: the host and the first joined archer.
    /// MULTI: every archer with a phone; each phone archer's card goes to the next two phone archers (in order, round
    /// the group), so each scorer has two; other cards (friends, guests) go to the two scorers with fewest cards.
    /// SELF: phone archers score themselves; the host scores friends and guests.
    /// Returns problems instead when the method can't work with these members.
    /// </summary>
    public static (List<Scorer> Scorers, List<string> Problems) Default(string method, IReadOnlyList<Member> members)
    {
        var ordered = members.OrderBy(m => m.SortOrder).ToList();
        var phones = ordered.Where(m => HasPhone(m.Kind)).ToList();
        var host = ordered.FirstOrDefault(m => m.Kind == Host);
        var scorers = new List<Scorer>();
        if (host is null) return (scorers, ["The group has no host."]);

        switch (method)
        {
            case Single:
                scorers.AddRange(ordered.Select(c => new Scorer(c.MemberId, host.MemberId)));
                break;
            case Double:
                var second = phones.FirstOrDefault(m => m.Kind == Joined);
                if (second is null) return ([], ["Double scoring needs a second archer to join on their own phone."]);
                foreach (var c in ordered)
                {
                    scorers.Add(new Scorer(c.MemberId, host.MemberId));
                    scorers.Add(new Scorer(c.MemberId, second.MemberId));
                }
                break;
            case Multi:
                if (phones.Count < 3) return ([], ["Three or more scorers need at least three archers on their own phones."]);
                var load = phones.ToDictionary(p => p.MemberId, _ => 0);
                for (var i = 0; i < phones.Count; i++)
                {
                    foreach (var s in new[] { phones[(i + 1) % phones.Count], phones[(i + 2) % phones.Count] })
                    {
                        scorers.Add(new Scorer(phones[i].MemberId, s.MemberId));
                        load[s.MemberId]++;
                    }
                }
                foreach (var c in ordered.Where(m => !HasPhone(m.Kind)))
                {
                    foreach (var s in phones.OrderBy(p => load[p.MemberId]).ThenBy(p => p.SortOrder).Take(2).ToList())
                    {
                        scorers.Add(new Scorer(c.MemberId, s.MemberId));
                        load[s.MemberId]++;
                    }
                }
                break;
            case Self:
                scorers.AddRange(ordered.Select(c => new Scorer(c.MemberId, HasPhone(c.Kind) ? c.MemberId : host.MemberId)));
                break;
            default:
                return ([], ["Unknown scoring method."]);
        }
        return (scorers, []);
    }

    /// <summary>Checks scorers sent by the host's phone against the method's rules.</summary>
    public static List<string> Validate(string method, IReadOnlyList<Member> members, IReadOnlyList<Scorer> scorers)
    {
        var problems = new List<string>();
        var byId = members.ToDictionary(m => m.MemberId);
        foreach (var s in scorers)
        {
            if (!byId.ContainsKey(s.CardMemberId)) { problems.Add("A scorer is set for someone who isn't in the group."); return problems; }
            if (!byId.TryGetValue(s.ScorerMemberId, out var scorer)) { problems.Add("A scorer isn't in the group."); return problems; }
            if (!HasPhone(scorer.Kind)) { problems.Add("Only archers with the group on their own phone can score."); return problems; }
        }
        if (scorers.Distinct().Count() != scorers.Count) { problems.Add("The same scorer is listed twice for one card."); return problems; }

        var perCard = scorers.GroupBy(s => s.CardMemberId).ToDictionary(g => g.Key, g => g.Select(s => s.ScorerMemberId).ToList());
        if (members.Any(m => !perCard.ContainsKey(m.MemberId))) { problems.Add("Every card needs a scorer."); return problems; }

        var needed = method is Double or Multi ? 2 : 1;
        if (perCard.Values.Any(v => v.Count != needed))
        {
            problems.Add(needed == 2 ? "Every card needs two different scorers." : "Every card needs one scorer.");
            return problems;
        }

        switch (method)
        {
            case Single:
            case Double:
                var first = perCard.Values.First().OrderBy(x => x).ToList();
                if (perCard.Values.Any(v => !v.OrderBy(x => x).SequenceEqual(first)))
                    problems.Add(method == Single ? "One scorer scores every card." : "The same two scorers score every card.");
                break;
            case Multi:
                if (perCard.Any(kv => kv.Value.Contains(kv.Key))) problems.Add("Nobody scores their own card.");
                var loads = scorers.GroupBy(s => s.ScorerMemberId).Select(g => g.Count()).ToList();
                if (loads.Count < 3) problems.Add("Three or more scorers are needed.");
                else if (loads.Max() - loads.Min() > 1) problems.Add("Cards must be shared out as evenly as possible.");
                break;
            case Self:
                foreach (var m in members.Where(m => HasPhone(m.Kind)))
                    if (perCard[m.MemberId][0] != m.MemberId) problems.Add("In self scoring, archers on their own phones score themselves.");
                break;
        }
        return problems.Distinct().ToList();
    }

    /// <summary>What has to match for two entries to be the same score (points alone could hide a different hit).</summary>
    public static string MatchKey(TargetDto t) => string.Join("|",
        t.Type, t.OverridePoints?.ToString() ?? "-", t.Points, t.Arrows ?? "",
        string.Join(";", (t.Animals ?? []).OrderBy(a => a.Index).Select(a => $"{a.Index}:{a.State}:{a.ArrowNo}:{a.Hit}:{a.Points}")));
}
