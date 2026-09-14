# Prototype Instructions

Run the local server yourself and open the preview in the in-app browser. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

## Confirmed Product Direction

- Product name: USBLink.
- Target: a fully functional Chinese Windows desktop app distributed as a Chinese Windows installer (Setup.exe), with shortcuts and Windows uninstall registration. The default build must produce an installer, not only a portable executable.
- Installer busy checks must let the user explicitly confirm ending the current user's USBLink processes and then continue (user decision, 2026-09-13). Explain that forced exit skips USB cleanup until the next app startup. Confirm process exit and session-lock release before proceeding; failed termination remains retryable/cancellable. Never kill silently: silent/passive installs must fail with code 1618 when busy.
- Upgrades must default to updating in place without uninstalling, retaining pairing/settings, the existing install directory, shortcuts and enabled auto-start. Keep uninstall-first as an explicit alternative.
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
- The user wants actual seamless device connection, not muted Windows sounds (2026-09-14). Preserve system sound settings; do not describe sound suppression, hidden reattachment, or moving the disconnect to an earlier step as achieving device continuity. Verify the selected driver and device protocol can meet the requirement before claiming support.
- Before the sole manual attach, verify fresh stable export records for the selected bus ID, VID/PID and source device path; socket reachability alone is insufficient while the source re-enumerates. The user explicitly prohibits even a bounded automatic retry within that click (2026-09-13).
- Pairing, ICMP and the usbipd/EasyTier background services do not prove that USBLink is open. Verify the paired application's authenticated live response continuously and before USB attachment, distinguish application presence from USB service readiness, and prepare TCP 3241 access for receiving-only computers too. Never fall back to service/ICMP reachability as application presence. On loss, clear stale remote exports while retaining actual local mounts only in the mounted section for disconnection.
- Normal exit and startup cleanup must handle both local USB sharing authorizations and received virtual USB mounts. Read back driver state to confirm detach; process both directions even when one fails. Do not show successful exit until both are clear. Force termination/power loss cleanup is deferred to the next startup.
- usbip-win2 versions older than 0.9.8.0 must be blocked because 0.9.7.8 has an upstream-confirmed memory-corruption and BSOD defect.
- Privileged operations must use USBLink's native `ShellExecuteExW` helper and structured DPAPI-protected task files. Do not reintroduce PowerShell as an elevation wrapper.
- Creating a new mesh must not unconditionally uninstall a service, and network readiness must be polled asynchronously instead of blocking the command for several seconds.
