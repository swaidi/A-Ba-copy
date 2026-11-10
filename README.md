# Screen Text Copy

A private, offline replacement for "Circle to Search" text copying on GrapheneOS.
Long-press a thin strip at the bottom edge of the screen, circle any text (in an image,
chat, web page or app), and the recognized text is copied to the clipboard.

- Text recognition: Tesseract (English and Arabic), fully on the device.
- No internet permission and no Google services.
- Updates: GitHub Releases, installed through Obtainium.

## Triggers

| Trigger | Needs accessibility service | Notes |
|---|---|---|
| Bottom-edge long-press | Yes | Closest to the Motorola gesture. Size and position are adjustable. |
| Quick toggle tile | — | Turns the service off in one tap (e.g. before banking apps). |
| Capture tile (fallback) | No | Uses screen capture; Android asks for consent each time — choose **Entire screen**. |

## First-time setup on the phone

1. Install the APK from the latest release (or through Obtainium).
2. Open **Settings → Apps → Screen Text Copy → ⋮ → Allow restricted settings**.
3. Open **Settings → Accessibility → Screen Text Copy trigger** and turn it on.
4. In the app, turn on **Show strip outline** and adjust the strip so it sits just above the
   navigation handle, then turn the outline off.
5. Optional: add the tiles from the Quick Settings edit screen.

## Obtainium (updates)

1. Add app → paste this repository URL.
2. Because the repository is private, add a GitHub personal access token
   (fine-grained, read-only access to this repository's **Contents**) in
   Obtainium's GitHub source settings.

## Build

Every push to `main` runs `.github/workflows/release.yml`, which downloads the OCR models,
builds a signed APK and publishes a release tagged `v1.0.<run number>`.

Required repository secrets: `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`,
`SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. Keep the keystore safe: losing it means
future updates cannot be installed over the existing app.
