# Prototype Instructions

Run the local server yourself and open the preview in the in-app browser. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

## Confirmed Product Direction

- Product name: USBLink.
- Target: a fully functional Chinese Windows desktop app distributed as a Chinese Windows installer (Setup.exe), with shortcuts and Windows uninstall registration. The default build must produce an installer, not only a portable executable.
- Visual target: Product Design option 1, the Fluent workspace mock generated for this project.
- The app supports both the USB-host and remote-client roles without exposing command-line operations.
- Device selection is intentionally unlimited; never reintroduce the original two-device cap.
- USB sharing is session-scoped: normal exit must revoke local usbipd sharing authorizations before closing, and startup must clean leftover authorizations (including unplugged devices) before allowing new operations. Never automatically restore sharing. Cleanup cancellation/failure must remain visible and retryable; do not claim sharing stopped or exit successfully before verifying it. Serialize cleanup with active operations and prevent multiple USBLink UI instances from clearing one another's shares.
- Use calm Windows 11 Fluent styling, compact utility copy, a single teal accent, and grouped rows instead of a dashboard-card mosaic.
- Component status must refresh when the window regains focus and continue polling while the settings page is open; users must never need to restart after installing a dependency.
- EasyTier 2.6.4 replaces Tailscale. Preserve the account-free create/join pairing-code flow and keep the embedded EasyTier files unmodified with their LGPL license and source notice.
- Pairing secrets must never be logged or stored in frontend persistence. The Rust backend protects the local profile with Windows DPAPI.
- The primary community relay is `tcp://183.230.36.171:11010`, with `tcp://107.172.5.203:11010` as a validated fallback. The retired `public.easytier.top` address must be migrated or surfaced as repairable, never retried indefinitely.
- Mesh status must distinguish a responsive local service from a successful public-relay handshake and show a concrete problem instead of an endless connecting state.
- Automatic USB/IP reattachment is prohibited. Every manual attach must use `--once` and be serialized to avoid multiplying third-party driver failures.
- A single Connect click must remain pending until the requested USB mounts are confirmed; preserve device rows during initialization and never require a second click merely to refresh success. Read-only confirmation polling must not resubmit attach commands.
- Pairing does not prove that the other computer is online. Verify peer reachability continuously and before USB attachment, distinguish computer presence from USB service availability, and disable new mounts when unreachable while retaining local mount records for manual disconnection.
- usbip-win2 versions older than 0.9.8.0 must be blocked because 0.9.7.8 has an upstream-confirmed memory-corruption and BSOD defect.
- Privileged operations must use USBLink's native `ShellExecuteExW` helper and structured DPAPI-protected task files. Do not reintroduce PowerShell as an elevation wrapper.
- Creating a new mesh must not unconditionally uninstall a service, and network readiness must be polled asynchronously instead of blocking the command for several seconds.
