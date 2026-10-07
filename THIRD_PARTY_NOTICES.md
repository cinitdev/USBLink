# Third-Party Notices

## Tauri NSIS installer template and Chinese translation

USBLink adapts the Tauri CLI 2.11.4 NSIS installer template and Simplified Chinese strings for retryable shutdown checks and in-place upgrades.

- Source: https://github.com/tauri-apps/tauri/tree/tauri-cli-v2.11.4/crates/tauri-bundler/src/bundle/windows/nsis
- Copyright (c) 2017 - Present Tauri Apps Contributors
- License used: MIT
- Included license: `src-tauri/LICENSE-TAURI-TEMPLATE.txt` (installed as `licenses/LICENSE-TAURI-TEMPLATE.txt`)
- Local modifications and validation: `src-tauri/INSTALLER.md`

## EasyTier 2.6.4

USBLink redistributes unmodified Windows binaries from EasyTier 2.6.4 as a separately executed networking component.

- Project: https://github.com/EasyTier/EasyTier
- Exact source revision: https://github.com/EasyTier/EasyTier/tree/8428a89d2dabc94c97d370ec607c6ca142473626
- Release: https://github.com/EasyTier/EasyTier/releases/tag/v2.6.4
- License: GNU Lesser General Public License v3.0
- Included license: `src-tauri/vendor/easytier/LICENSE-EasyTier.txt`
- Official release archive SHA-256: `27AF91E270E554709B048BD32327FEFD2DFCE5062AE1E8701AF7550C6F525F84`

The EasyTier files are not linked into USBLink and are not modified. USBLink extracts and launches them as separate processes/services.

The Android companion also redistributes the unmodified ARM64 `easytier-core` and `easytier-cli` from the official `Easytier-Magisk-v2.6.4.zip`. Its original module scripts are not used. Archive and individual binary hashes, LGPL/GPL license texts, and the corresponding source archive are documented in [the Android notice](mobile/vendor/easytier/NOTICE.md) and included in the module's `licenses` directory. Fetch verified development binaries with `mobile/scripts/fetch-easytier.ps1`; generated binaries are excluded from Git.
