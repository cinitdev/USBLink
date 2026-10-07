# EasyTier 2.6.4

USBLink Mobile includes **unmodified** `easytier-core` and `easytier-cli` ARM64 binaries from the official EasyTier Android/Magisk release. The upstream module's scripts, global routing changes, wake lock, web service, and hotspot forwarding are not bundled or executed. USBLink runs the two original binaries as separate processes.

- Copyright: EasyTier contributors.
- License: GNU Lesser General Public License v3.0. See `LICENSE-EasyTier.txt` and the incorporated GNU GPL v3 in `LICENSE-GPL-3.0.txt`.
- Project and corresponding source: <https://github.com/EasyTier/EasyTier/tree/v2.6.4>
- Source archive: <https://github.com/EasyTier/EasyTier/archive/refs/tags/v2.6.4.tar.gz>
- Official binary release: <https://github.com/EasyTier/EasyTier/releases/tag/v2.6.4>
- Archive: `Easytier-Magisk-v2.6.4.zip` (14,099,136 bytes).
- Archive SHA-256: `39a6b4fa21d9fdc83d3b38c90562f610c0986ecc089c4026c3be22a0ab27c5e5`.

`manifest.json` records the download URL, archive checksum, and individual binary checksums. Run `mobile/scripts/fetch-easytier.ps1` to reproduce extraction. USBLink does not modify, statically link, or relabel these binaries. They remain replaceable in the module's `bin` directory; use a compatible protocol and CLI version when replacing them. No restriction on reverse engineering for debugging changes to these libraries is added by USBLink.

When distributing a release, retain this notice and license files and ensure corresponding EasyTier source remains available alongside the release or through the referenced source archive. Any future binary modifications require corresponding modified source and notices.
