# Alef_B_scan

A private, offline replacement for "Circle to Search" text copying on GrapheneOS.
Long-press a thin strip at the bottom edge of the screen, then tap or circle any text
(in a chat, web page, app or image) and copy it.

- Text shown by apps (chats, web pages, documents) is read directly, so it is exact in Arabic and English.
- Text inside images is recognized on the phone with Tesseract (high-accuracy Arabic, fast English).
- No internet permission and no Google services.
- Updates: GitHub Releases, installed through Obtainium.

## Selecting text

- **Tap a word** to select it, then drag the two handles to extend the selection.
- **Circle text** to select every word the circle touches (whole words).
- The glowing bar under the selection offers **Copy**, **Share**, **Sentence → Paragraph**
  (grows the selection step by step) and **All screen**.

## Triggers

| Trigger | Needs accessibility service | Notes |
|---|---|---|
| Bottom-edge long-press | Yes | Closest to the Motorola gesture. Size and position are adjustable. |
| Quick toggle tile | — | Turns the service off in one tap (e.g. before banking apps). |
| Capture tile (fallback) | No | Uses screen capture; Android asks for consent each time — choose **Entire screen**. |

## First-time setup on the phone

1. Install the APK from the latest release (or through Obtainium).
2. Open **Settings → Apps → Alef_B_scan → ⋮ → Allow restricted settings**.
3. Open **Settings → Accessibility → Alef_B_scan trigger** and turn it on.
4. In the app, turn on **Show strip outline** and adjust the strip so it sits just above the
   navigation handle, then turn the outline off.
5. Optional: add the tiles from the Quick Settings edit screen.

## Obtainium (updates)

Add app → App source URL: `https://github.com/swaidi/Alef-B-scan` → Add.

## Build

Every push to `main` runs `.github/workflows/release.yml`, which downloads the OCR models,
builds a signed arm64 APK and publishes a release tagged `v1.0.<run number>`.
Builds started on other branches produce a test APK only (no release).

Required repository secrets: `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Keep the keystore safe: losing it means
future updates cannot be installed over the existing app.
