# Mobile 0.3.1 UI and naming validation

Date: 2026-10-07. This report covers the experimental module build, automated checks, and a local browser preview. It does not claim that 0.3.1 has been flashed or accepted on the phone.

## Changes

- Phone titles use ROM-provided market names, falling back to the configured device name and original model code. The mesh hostname uses the configured name. Original model codes remain visible in WebUI device details. No model lookup table or desktop ADB changes are involved.
- The user confirmed a blue/white light palette and navy dark palette. The existing Devices / Connections / Settings layout is retained, with connection status surfaced above the optional first-use guide.
- Read-only status refresh runs every 2 seconds while sharing/preparing and every 5 seconds while idle. Hidden pages suspend polling and immediately synchronize on resume. Failed reads back off to at most 15 seconds; expired observations are not displayed as confirmed live state. No mutation or USB attachment is retried.

## Evidence

- Read-only queries on the already connected Redmi K40 / Android 12 returned `Redmi K40` for ROM market-name properties and configured device name; the original model code was `M2012K11AC`. These queries did not restart adbd, change sharing, or reattach the device.
- The experimental build passed 56 backend, 52 mesh, 22 naming, 50 presence, 94 adbd safety/recovery, 575 USB/IP protocol and 27 module lifecycle/authentication checks, plus 17 WebUI tests. DEX and Vite compilation completed successfully.
- In the explicit sample-data browser preview, checked navigation, expanding the first-use guide, creating a sample network, sharing-switch pending/completed feedback, and the automatic-sync timestamp. These actions did not operate the phone.
- A draft relay address remained unchanged across several automatic refreshes without being saved. No horizontal overflow was observed at 390 x 844 or 320 x 740.
- Light-mode browser screenshot: [390px preview](screenshots/webui-0.3.1-light.jpg). Navy dark styles were inspected in source; the user's OS theme was not changed for this check.
- All 22 ZIP entries were read successfully. `module.prop` reports 0.3.1 / 301. The daemon JAR contains `classes.dex`, and compiled WebUI assets are present. The SHA256 sidecar matches.

## Artifact

`release/USBLink-Mobile-0.3.1-usb-adb-experimental-arm64.zip`

- Size: 8,484,476 bytes
- SHA256: `1d4be5dcbb46110d46a48f57147b430ef440f74e8c925be5c45d4a7d92ba7f9c`
- Compatible with existing Windows USBLink 0.3.11. This change does not add MTP, Fastboot, external-phone or U-disk sharing.
