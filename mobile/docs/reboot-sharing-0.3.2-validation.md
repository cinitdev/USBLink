# Mobile 0.3.2 reboot sharing validation

Date: 2026-10-07. The phone was inspected read-only; this build has not been installed or reboot-tested on the phone.

## Observed on K40 / Android 12 / APatch

- Installed module: 0.3.1. Live status reports `sharing.enabled=true`, `active=false`, `state=error`, with “调试端口防护已丢失，共享已暂停，请关闭共享重试”. The temporary adbd port has already been restored by the failure cleanup.
- Daemon identity in the status snapshot shows startup at about 16.65 seconds after boot. Android event logs report AMS ready at 23.432 seconds and boot animation complete at 29.295 seconds.
- IPv4 INPUT/OUTPUT currently begin with the vendor `nm_mdmprxy_doze_mode_skip` jump. Its chain contains a mark-dependent ACCEPT rule. Treating that rule as harmless, ignoring chain order or merely hiding the error would be incorrect.
- Old code starts the persisted share as soon as the mesh IP exists, with no Android boot gate. Any subsequent priority displacement makes the controller revoke sharing and latch an error. After this cleanup, live rules cannot establish the exact earlier displacement/deletion event; the startup timing and relevant vendor chains were confirmed separately.

## Fix and checks

- Keep the saved sharing preference. Before the first start, wait asynchronously for `sys.boot_completed`, a 10-second preparation interval and 6 consecutive seconds of stable IPv4/IPv6 filter-chain observations. Firewall unavailability or continuous changes are bounded to 60 seconds after boot completion. No adbd changes occur during preparation; an explicit off cancels startup durably.
- Keep the strict runtime verification. Service maintenance may repair priority only after confirming all exact owned IPv4 and IPv6 rules, including a recheck immediately before insertion. It inserts restrictive rules first and verifies the result, without restarting adbd, changing its port or reattaching USB. A genuine missing rule fails closed. A limit of three order repairs bounds duplicate accumulation during continuous external interference; stop cleanup removes exact owned duplicates.
- WebUI displays normal preparation as “等待系统启动就绪” / “共享准备中”, with an informational hint rather than an error or a misleading closed-state label. Pure status reads never run this maintenance.
- Passed 50 reboot-startup checks, 113 adbd/firewall safety checks, 56 backend, 52 mesh, 22 naming, 50 presence, 575 USB/IP protocol and 27 experimental module lifecycle checks; 17 WebUI tests passed. After the final preparation-copy refinement, DEX and WebUI compilation and packaging succeeded again without repeating the unchanged backend suite.
- Local WebUI preview loaded successfully. No desktop USB attachment, phone installation, sharing toggle, adbd restart or phone reboot was performed for this validation.

## Artifact

`release/USBLink-Mobile-0.3.2-usb-adb-experimental-arm64.zip`

- Size: 8,486,401 bytes
- SHA256: `5b96f259005840d99cd3e19d764613e7aa0cd98a468ea254f601623bbab6d89a`
- Existing Windows USBLink 0.3.11 remains compatible.
