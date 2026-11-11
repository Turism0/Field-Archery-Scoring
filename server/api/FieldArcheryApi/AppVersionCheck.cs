// Update required (3.2.05, Pete): the apps say which version they are, and the server can refuse ones too old for it.
// The Android app sends "X-App-Version: android/66" (its versionCode); the web app "X-App-Version: web/3.2.05".
// Oldest supported versions are in appsettings.json, "MinVersions": { "Android": 66, "Web": "3.2.05" }; 0 / "" = any.
// A call from an older app gets 426 "update_required", and the app shows "Please update". Calls with no header (older
// apps from before 3.2.05, scripts, monitoring) are always let through, so raise the minimums only once phones have
// 3.2.05 or later. Signing out is always allowed.

namespace FieldArcheryApi;

public static class AppVersionCheck
{
    public const string Header = "X-App-Version";

    public static void UseAppVersionCheck(this WebApplication app)
    {
        var minAndroid = app.Configuration.GetValue("MinVersions:Android", 0);
        var minWeb = Parse(app.Configuration["MinVersions:Web"]);
        app.Use(async (ctx, next) =>
        {
            var sent = ctx.Request.Headers[Header].ToString();
            if (sent.Length > 0 && ctx.Request.Path != "/api/auth/logout" && TooOld(sent, minAndroid, minWeb))
            {
                ctx.Response.StatusCode = StatusCodes.Status426UpgradeRequired;
                await ctx.Response.WriteAsJsonAsync(new
                {
                    error = "update_required",
                    message = "This version of the app is too old for the server. Please update it.",
                });
                return;
            }
            await next();
        });
    }

    /// <summary>"android/66" or "web/3.2.05" against the minimums. Anything unreadable is let through.</summary>
    public static bool TooOld(string sent, int minAndroid, int[] minWeb)
    {
        var slash = sent.IndexOf('/');
        if (slash < 0) return false;
        var kind = sent[..slash].Trim().ToLowerInvariant();
        var version = sent[(slash + 1)..].Trim();
        if (kind == "android") return minAndroid > 0 && int.TryParse(version, out var code) && code < minAndroid;
        if (kind == "web") return minWeb.Length > 0 && Parse(version) is { Length: > 0 } v && Compare(v, minWeb) < 0;
        return false;
    }

    /// <summary>"3.2.05" → [3, 2, 5]; empty or unreadable → [].</summary>
    public static int[] Parse(string? version)
    {
        if (string.IsNullOrWhiteSpace(version)) return [];
        var parts = version.Trim().Split('.');
        var numbers = new int[parts.Length];
        for (var i = 0; i < parts.Length; i++)
        {
            if (!int.TryParse(parts[i], out numbers[i])) return [];
        }
        return numbers;
    }

    /// <summary>Part by part, missing parts counting as 0 (so 3.2 = 3.2.0).</summary>
    public static int Compare(int[] a, int[] b)
    {
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return 0;
    }
}
