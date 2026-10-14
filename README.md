# JARVIS Companion (Android)

The phone side of [JARVIS for Windows](https://github.com/HitEmWitaPoopSok/Jarvis-for-windows).
From anywhere you can:

- **Talk to JARVIS** by voice or text.
- **Find a file on your PC and download it**: forgot the SVG or PNG for a laser job? Search by name
  (filters for Laser, SVG, PNG, Vector, PDF), tap Download, and open it in LightBurn or share it.
  Or just say *"find the logo SVG and send it to my phone"* and it appears under **Waiting from JARVIS**.
- **Speak to the room.** The PC chimes and says your message aloud, and (if you ask) carries the first
  reply back to your phone. It never listens silently.
- See his status, press **Unlock** when the voice lock is on, and get **background alerts**
  (reminders, builds, "file ready") through ntfy.

A request from the phone can't run commands, open terminals or browsers, start builds or drive other
apps on the PC; that is enforced on the PC, not just asked of the model.

## Setup
1. Install **Tailscale** on the PC and the phone and sign in to the same account (free).
2. Update JARVIS for Windows to 1.4.0 or later. In his HUD press **Pair a phone**.
3. Install this APK (download from [Releases](../../releases)), open it, enter the address and code.
4. For alerts with the app closed: install nothing extra; paste your ntfy topic into **Status**
   (the same topic as in JARVIS's settings; pairing fills it in) and switch **Background alerts** on.
   Exempt the app from battery optimisation if Android stops it.

## How it connects
Plain HTTP to the PC's Tailscale address (`100.x.x.x:8341`). Tailscale encrypts the link end to end,
and every request carries a per-phone token issued when you pair with a one-time code. The PC listens on
the Tailscale address only, never on the whole network and never on the internet.

## Building
Android Studio isn't needed: `gradle test assembleRelease` (JDK 17, Android SDK 35) builds
`app/build/outputs/apk/release/app-release.apk`. CI does this on every push and publishes the APK.
The APK is signed with a fixed key committed in `app/jarvis.keystore`, deliberately, so updates install
over each other. It is a personal sideloaded app; that key protects nothing else.

No third-party libraries: plain Android views, `HttpURLConnection`, the system speech recogniser and
text-to-speech.
