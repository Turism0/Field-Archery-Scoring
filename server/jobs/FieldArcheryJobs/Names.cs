using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace FieldArcheryJobs;

/// <summary>
/// Archers are only needed as "the same person as last year", so the database keeps a keyed hash of the tidied name,
/// never the name. HMAC-SHA256 with a secret from appsettings.Production.json, so a name can't be guessed and looked up.
/// The secret must never change once results are stored (everyone would become a new person).
/// </summary>
public static partial class Names
{
    /// <summary>Lower case, letters and spaces only, hyphens as spaces, "Mc Dermott" as "mcdermott".</summary>
    public static string Normalise(string name)
    {
        var n = name.ToLowerInvariant().Replace('-', ' ');
        n = NotLetter().Replace(n, "");
        n = McSpace().Replace(n, "mc");
        return string.Join(' ', n.Split(' ', StringSplitOptions.RemoveEmptyEntries));
    }

    public static byte[] Key(string name, string secret)
    {
        using var h = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return h.ComputeHash(Encoding.UTF8.GetBytes(Normalise(name)));
    }

    [GeneratedRegex("[^a-z ]")] private static partial Regex NotLetter();
    [GeneratedRegex(@"\bmc ")] private static partial Regex McSpace();

    /// <summary>Clubs as a simple key (letters only), for telling apart two archers with the same name.</summary>
    public static string ClubKey(string club) => new(club.ToLowerInvariant().Where(c => c is >= 'a' and <= 'z').ToArray());

    /// <summary>FNV-1a, 32 bit: a fixed, language-independent hash used to pick test samples (same as the Python reference).</summary>
    public static uint Fnv(string s)
    {
        uint h = 0x811C9DC5;
        foreach (var b in Encoding.UTF8.GetBytes(s)) { h ^= b; h *= 0x01000193; }
        return h;
    }
}
