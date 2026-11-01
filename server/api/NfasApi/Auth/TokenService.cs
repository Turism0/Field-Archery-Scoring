using System.Security.Cryptography;
using System.Text;
using Dapper;
using Microsoft.Data.SqlClient;
using NfasApi.Data;

namespace NfasApi.Auth;

public sealed record IssuedToken(string Token, DateTime ExpiresUtc);

public sealed record TokenArcher(Guid TokenId, Guid ArcherId, string Username, string Role);

/// <summary>
/// Sign-in tokens, one per phone. The phone keeps the token instead of the password.
/// Only a SHA-256 hash of each token is stored, so a copy of the database can't be used to sign in.
/// Tokens last a year and the expiry slides forward while the phone keeps using it.
/// </summary>
public sealed class TokenService(Db db)
{
    public static readonly TimeSpan Lifetime = TimeSpan.FromDays(365);

    public async Task<IssuedToken> IssueAsync(SqlConnection conn, Guid archerId, string? deviceName, CancellationToken ct)
    {
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));   // 64 hex characters
        var expires = DateTime.UtcNow.Add(Lifetime);
        await conn.ExecuteAsync(new CommandDefinition("""
            INSERT dbo.AuthToken (ArcherId, TokenHash, DeviceName, ExpiresUtc, LastUsedUtc)
            VALUES (@archerId, @hash, @deviceName, @expires, SYSUTCDATETIME());
            """,
            new { archerId, hash = Hash(token), deviceName = Shorten(deviceName, 100), expires },
            cancellationToken: ct));
        return new IssuedToken(token, expires);
    }

    /// <summary>
    /// Returns the archer for a valid, unexpired, unrevoked token, or null. The role is read from the
    /// database on every request, so a role change takes effect straight away.
    /// </summary>
    public async Task<TokenArcher?> ValidateAsync(string token, CancellationToken ct)
    {
        if (token.Length != 64) return null;
        await using var conn = await db.OpenAsync(ct);
        var row = await conn.QuerySingleOrDefaultAsync<TokenRow>(new CommandDefinition("""
            SELECT t.TokenId, t.ArcherId, a.Username, a.Role, t.LastUsedUtc
            FROM dbo.AuthToken t
            JOIN dbo.Archer a ON a.ArcherId = t.ArcherId
            WHERE t.TokenHash = @hash
              AND t.RevokedUtc IS NULL
              AND t.ExpiresUtc > SYSUTCDATETIME()
              AND a.IsDisabled = 0;
            """, new { hash = Hash(token) }, cancellationToken: ct));
        if (row is null) return null;

        // Slide the expiry forward, at most once an hour to keep writes down.
        if (row.LastUsedUtc is null || row.LastUsedUtc < DateTime.UtcNow.AddHours(-1))
        {
            await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.AuthToken
                SET LastUsedUtc = SYSUTCDATETIME(), ExpiresUtc = DATEADD(DAY, @days, SYSUTCDATETIME())
                WHERE TokenId = @tokenId;
                """, new { tokenId = row.TokenId, days = (int)Lifetime.TotalDays }, cancellationToken: ct));
        }
        return new TokenArcher(row.TokenId, row.ArcherId, row.Username, row.Role);
    }

    public async Task RevokeAsync(Guid tokenId, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.AuthToken SET RevokedUtc = SYSUTCDATETIME() WHERE TokenId = @tokenId AND RevokedUtc IS NULL;",
            new { tokenId }, cancellationToken: ct));
    }

    private static byte[] Hash(string token) => SHA256.HashData(Encoding.UTF8.GetBytes(token));

    private static string? Shorten(string? s, int max)
    {
        if (string.IsNullOrWhiteSpace(s)) return null;
        var t = s.Trim();
        return t.Length > max ? t[..max] : t;
    }

    private sealed record TokenRow(Guid TokenId, Guid ArcherId, string Username, string Role, DateTime? LastUsedUtc);
}
