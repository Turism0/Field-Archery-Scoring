using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace NfasApi.Auth;

/// <summary>Reads "Authorization: Bearer &lt;token&gt;" and signs the request in as that archer.</summary>
public sealed class TokenAuthHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    TokenService tokens)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "NfasToken";
    public const string TokenIdClaim = "nfas_token_id";

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        string? header = Request.Headers.Authorization;
        if (string.IsNullOrEmpty(header) || !header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase))
        {
            return AuthenticateResult.NoResult();
        }

        var archer = await tokens.ValidateAsync(header["Bearer ".Length..].Trim(), Context.RequestAborted);
        if (archer is null)
        {
            return AuthenticateResult.Fail("Invalid or expired token");
        }

        var identity = new ClaimsIdentity(
            [
                new Claim(ClaimTypes.NameIdentifier, archer.ArcherId.ToString()),
                new Claim(ClaimTypes.Name, archer.Username),
                new Claim(TokenIdClaim, archer.TokenId.ToString()),
            ],
            SchemeName);
        return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(identity), SchemeName));
    }
}

public static class ClaimsExtensions
{
    public static Guid ArcherId(this ClaimsPrincipal user) =>
        Guid.Parse(user.FindFirstValue(ClaimTypes.NameIdentifier)!);

    public static Guid TokenId(this ClaimsPrincipal user) =>
        Guid.Parse(user.FindFirstValue(TokenAuthHandler.TokenIdClaim)!);
}
