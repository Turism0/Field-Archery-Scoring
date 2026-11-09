# Publishing on Google Play

Pete's developer account is **personal, made after November 2023**, so Google needs a **closed test with at least 12
testers, opted in for 14 days in a row**, before the app can go to Production. Prepared Nov 2026 (app 2.4.02, API 2.4.02).
Check the Play Console for anything that's changed since.

## 1. Before uploading

- **API 2.4.02 published** (account deletion, privacy page, nearby search in the body). Check
  https://fieldarchery.fnurble.co.uk/privacy and https://fieldarchery.fnurble.co.uk/delete-account open from a phone
  on mobile data (Google checks them from outside your network).
- **Target API 36** (Android 16): new apps must target it since 31 Aug 2026. Done in 2.4.02.
- **Upload key:** Android Studio → Build → Generate Signed App Bundle → Android App Bundle → Create new key store.
  Keep the `.jks` file and both passwords somewhere safe and backed up (not in Git). Google holds the real signing key
  (Play App Signing); this upload key can be reset through Google if lost, but it's slow.
- Build the **release** bundle (`app-release.aab`). Each upload needs a higher versionCode (2.4.02 is 41).

## 2. Play Console: create the app

- App name **Field Archery Scoring**, default language English (United Kingdom), App, Free.
- Declarations: follows the Developer Programme Policies, US export laws.

## 3. App content (Policy → App content)

| Section | Answer |
|---|---|
| Privacy policy | https://fieldarchery.fnurble.co.uk/privacy |
| App access | Some features need an account. Give reviewers a test account (make one in the app, e.g. `playreview`, with a few rounds and a friend) and say: "Sign in from Profile. Live groups need two phones; everything else works on one." |
| Ads | No ads |
| Content rating | Questionnaire: choose the category **All other app types** (it isn't a game). No violence, sexual content, profanity, drugs, gambling. Users can interact (friends) – answer Yes to "users can interact or exchange content" (friends see each other's rounds; no chat). Expect PEGI 3 / Everyone. |
| Target audience | **13–15, 16–17 and 18 and over** (not under 13; Pete, Nov 2026). Not designed for children; the store listing doesn't target children. |
| News app | No |
| Health / government / financial features | None |
| Foreground service permissions | **Connected device**: "During a live group (several archers scoring one round on their own phones), the app keeps exchanging scores with the other phones over Bluetooth / Wi-Fi (Nearby Connections) and the server while the screen is off, until the group is finished. A notification shows while it runs, with a Stop button." Google asks for a short video: make a live group, turn the screen off, show the notification and Stop. |
| Account deletion | In the app: Profile → Delete account. Web: https://fieldarchery.fnurble.co.uk/delete-account. Deletes everything on the server straight away. |
| Data safety | Below |

### Data safety

- Does the app collect or share user data? **Yes** (collect; nothing is shared).
- Encrypted in transit: **Yes**. Users can request deletion: **Yes** (the links above).
- Data types, all **collected, not shared**, **optional** (the app works without an account), purpose **App functionality** (and **Account management** where shown):

| Category → type | Notes |
|---|---|
| Personal info → **User IDs** | Username (Account management). |
| Location → **Precise location** | Sent when a round starts to find the shoot location; **processed ephemerally** (not stored). |
| App activity → **Other user-generated content** | Rounds and scores, friends, live groups, names of shoot locations and of guests in a group. |
| App info and performance | Not collected. |
| Device or other IDs | Not collected (the server notes each phone's make and model for the "signed-in phones" list; Google's categories don't include this). |

Not collected: name, email, phone, address, contacts, photos, messages, financial, health, web history, advertising ID.
Real name and date of birth (Profile → About you) never leave the phone.

## 4. Store listing (Grow → Store presence → Main store listing)

- **Short description** (80 max): Score field archery rounds on your phone – on your own or as a group, no signal needed.
- **Full description:**

  > Score Big Game and Woodsman field archery rounds on your phone, target by target, with or without signal.
  >
  > • Score on your own, or a whole group on one phone, or every archer on their own phone in a live group – with
  >   double scoring and 3+ scorers that only save a score when both scorers agree.
  > • Live groups work with no signal: phones share scores directly over Bluetooth, and catch up with the server later.
  > • Scorecards, history, personal bests, trends and season summaries.
  > • A handicap worked out from years of National and 3D Championship results, so any bow style and class compare
  >   fairly – plus a calculator.
  > • Shoot locations found by GPS, with a map, and each location's averages by bow style and class.
  > • Friends: see each other's finished scorecards, and accept rounds a friend scored for you.
  > • An account is optional, with no email needed. Your name and date of birth never leave your phone. No ads.
  >
  > Not affiliated with the National Field Archery Society.

- **App icon** 512 × 512 PNG (from the launcher icon), **feature graphic** 1024 × 500, **phone screenshots** at least 2
  (suggest: Home, scoring a target, scorecard, Stats, location page, live group lobby). Category **Sports**. Contact email
  fieldarcheryscoring@gmail.com.

## 5. Closed test (the 12-tester rule)

1. Testing → Closed testing → create a track (e.g. "Archers"), upload the `.aab`, add testers by **email list** or a
   **Google Group** (easier: one group, invite people to join it).
2. Countries: United Kingdom (add more if wanted).
3. Send testers the **opt-in link**. They must opt in and **stay opted in for 14 days**; at least 12 at once. Ask them to
   install it and actually use it (Google asks about tester engagement and feedback).
4. Fix anything they find; upload new versions to the same track (versionCode up each time).
5. After 14 days: Dashboard → **Apply for production access**, answer the questions about the test, then create a
   Production release. Review usually takes a few days.

## 6. Each update after that

Build a signed bundle with a higher versionCode → Production (or the test track first) → release notes from the README.
