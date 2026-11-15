# Keeping older apps working (3.2.15)

Phones don't all update at once, and some never do. **Keeping older versions of the app working is preferred, not
required.** Don't give up functionality or efficiency to keep an old version working: when a change is better made in
a way old apps can't follow, make it, and turn those apps away cleanly (below) so they show "Update required for online
features" instead of going wrong.

## Prefer: changes old apps can live with

When it costs nothing (or very little), change the API like this:

1. **Add rather than take away.** New endpoints, new optional fields in requests and new fields in replies are safe.
   Removing or renaming an endpoint or field, changing what a field means, or changing its kind of value (a number to
   a string, say) breaks old apps.
2. **New request fields are optional.** An old app won't send them, so behave as before when they're missing (nullable
   with a sensible default, like `roundType` → Big Game, `move` → false).
3. **Don't make old requests stricter** than they need to be.
4. **Old values keep their meaning.** New values can be added to a list (a new target type, a new age group). Older apps
   skip a downloaded round they can't read rather than stopping, but they can't show it.
5. **The apps ignore what they don't know.** Both apps ignore extra fields in replies and treat missing optional fields
   as empty, so adding fields is always safe.

## When a change breaks older apps: turn them away

If the better change can't be followed by older versions (a new shape for a reply, a different sign-in, a field that has
to change, a faster way of syncing), make the change and stop those versions connecting:

1. Ship the app version that handles the change first (and the web app with the API, as usual).
2. In the same API deploy, raise the minimums in the server's `appsettings.json` to that version, then restart the app
   pool:
   ```json
   "MinVersions": { "Android": 78, "Web": "3.2.16" }
   ```
   `Android` is the oldest versionCode allowed and `Web` the oldest web app version; 0 / "" allows any.
3. Older apps then get `426 update_required` from every call except sign out, so they never get a reply they'd misread.
   Home shows **"Update required for online features"**: scoring keeps working on the phone; backup, friends, groups and
   shoot locations stop until they update, then their rounds back up as normal. The web app updates itself (its banner
   has "Update now").
4. Note it in the README entry for that version ("breaking: MinVersions raised to …").

Raising the minimum also turns away anyone who hasn't had the update from Play yet, so if it's not urgent, give it a
day or two after the release is live (Play Console → Statistics shows installs by version) before deploying the API.
Apps from before 3.2.05 send no version header and can't be turned away this way; there are none on Play (its first
release was 3.2.10).

## Test after every API deploy

Run `server/tests/Test-OldApps.ps1`. It plays the part of older apps (`android/66`, `web/3.2.05`;
a round without any newer fields) and checks every field they read is still there. For a version below the minimums it
expects the clean `426 update_required` instead. A PROBLEM line means an old app would go wrong rather than be told to
update: either keep the change compatible, or raise `MinVersions`.

## Update available

Separately, the Android app asks Google Play (in-app updates) whether a newer version is there for that person, and
Home shows **"Update available"** with Update / Later. It only appears once Play actually has the update, so deploying
the API before Google has approved the matching app is fine (unless the minimums are raised, as above).
