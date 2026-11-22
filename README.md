# GottaGo – nearby toilet finder (Android)

One big **I NEED TO GO** button finds the closest open toilet and walks you there.

## Features
- Uses your GPS (no Google Play services needed) and searches OpenStreetMap for toilets within 1 km, widening to 2.5 km and then 5 km if needed.
- Includes places that usually have toilets (malls, mosques, petrol stations), shown as faded "ask staff" pins.
- Skips places that are closed right now, using OpenStreetMap opening hours.
- Emergency mode: big distance and walking time, a walking route on the map, the next turn, **Open in Google Maps** and **Next closest**.
- Toilet details: free or paid, wheelchair access, baby changing, unisex, hours, source.
- **Add a toilet** for everyone. It shows as "NEW" until 3 people confirm it.
- **Remove a toilet** by community vote: it disappears for everyone after 3 net removal votes. A "still here" confirmation cancels one removal.
- Dark map, with the "© OpenStreetMap contributors" credit shown as required.

## Build the APK (GitHub Actions)
1. Create a new GitHub repo and upload everything in this folder (including the hidden `.github` folder).
2. Pushing to `main` starts **Actions → Build APK**. You can also run it by hand with "Run workflow".
3. When it finishes, open the run and download the **GottaGo-apk** artifact. Unzip it and install `app-debug.apk`.

The APK is signed with the included `app/debug.keystore`, so each new build installs over the old one.

## Turn on add / remove (free Firebase, about 5 minutes)
Without this the app still finds toilets; only add, remove and confirm are switched off.

1. Go to https://console.firebase.google.com → **Add project** (Google Analytics not needed).
2. **Build → Authentication → Get started → Sign-in method → Anonymous → Enable.**
3. **Build → Firestore Database → Create database** (production mode, pick a location near your users).
4. In Firestore, open the **Rules** tab, paste in the contents of `firestore.rules` and click **Publish**.
5. Click **Project settings** (gear icon) and copy the **Project ID** and the **Web API Key**.
6. In your GitHub repo, go to **Settings → Secrets and variables → Actions → New repository secret** and add:
   - `FIREBASE_PROJECT_ID` = your project ID
   - `FIREBASE_API_KEY` = your Web API key
7. Run the workflow again. The new APK has add and remove switched on.

## Where the data comes from
- Toilets: OpenStreetMap via the Overpass API (free, no key).
- Map tiles: OpenStreetMap.
- Walking routes: routing.openstreetmap.de (OSRM, foot profile).

These are free shared services with fair-use limits, which is fine for a launch. If the app grows to thousands of daily users, host your own Overpass and tile server or use a paid tile provider.

## Project layout
`app/src/main/java/com/gottago/app/`
- `MainActivity` – map, emergency button, nearest toilet card
- `EmergencyActivity` – walking mode to the chosen toilet
- `DetailsActivity`, `AddToiletActivity`, `RemoveToiletActivity`
- `Overpass` (OpenStreetMap search), `OpeningHours` (open now?), `Repo` (merge and rank)
- `Community` (Firebase add and votes), `RouteClient` (walking route), `LocationHelper`, `MapUtil`
