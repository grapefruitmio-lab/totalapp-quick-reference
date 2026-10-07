# Quick Reference 0.1.0-alpha.1

First experimental public alpha of Quick Reference, an Android-first reference workspace built around selected-text ingress, multi-provider lookup, persistent navigation/workspace state, an explicit Working Stack, and experimental Reference Graph enrichment.

## Tested scope

- Built and installed from the public-alpha candidate source.
- Exercised on a physical Android 14 device.
- Deterministic in-app smoke suite: 14/14 assertions passing.
- Tested flows include PROCESS_TEXT ingress, Wikipedia search and article opening, provider switching, language/reference traversal, Working Stack persistence, Navigation History, Reference Graph enrichment, and verified diagnostics quick-save/readback.

## Known issues

- Rare search-input rollback to an earlier/default state has previously been observed and remains under investigation, although it was not reproduced in the final candidate check.
- Some discussion/source targets legitimately return HTTP 404.
- Network providers can rate-limit requests.
- Reference Graph enrichment and mobile workspace/navigation semantics remain experimental.
- UI, iconography, accessibility, and broad device compatibility are not final.

## APK distribution

The first CodeAssist-exported APK inspected during release preparation is debug-signed. It is an acceptance artifact and is not the preferred public distribution APK. A stable release-signing identity should be established before publishing an APK intended for future in-place updates.

## Identity and provenance

Public version: `0.1.0-alpha.1`

Internal tested lineage: `v0.1q-workspace-transaction` / versionCode 18.

See `PUBLIC_RELEASE_PROVENANCE.md` for implementation hashes and the deliberate public-release metadata delta.

## Licensing

This release is public source for inspection and evaluation. No open-source license is granted at this stage. See `RIGHTS.md`.
