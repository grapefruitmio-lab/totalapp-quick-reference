# Quick Reference

Quick Reference is an experimental Android-first reference workspace for moving from selected text to multi-provider reference lookup without losing the working context around the query.

Public release target: **0.1.0-alpha.1**

## What it explores

- Android `PROCESS_TEXT` / text-share ingress
- Multi-provider lookup: Wikipedia, Wiktionary, Wikidata, Wikisource, OpenAlex
- Provider/language switching while retaining the working query
- Search-result and document navigation state
- Persistent Navigation History
- Explicit Working Stack
- Article representations such as read / history / discussion / languages where supported
- Experimental Reference Graph enrichment through Wikidata identity and sitelinks
- Content-bearing Reference Session events kept separate from operational diagnostics

The long-term direction is a mobile-first **Reference Workspace** in which query state, visited references, working references, discovered-but-unvisited references, evidence, provenance, and later AI-assisted reference bundles can be represented explicitly rather than being scattered across browser tabs and app history.

## Current status

This is an **experimental public alpha**, not a finished consumer application.

The `0.1.0-alpha.1` source is derived from the tested internal lineage `v0.1q-workspace-transaction` (versionCode 18). The final candidate source was built and installed on a physical Android 14 device, and the deterministic in-app smoke suite reported **14/14** passing assertions during the final candidate check.

See `PUBLIC_RELEASE_PROVENANCE.md` and `RELEASE_NOTES_0.1.0-alpha.1.md` for the exact release identity, tested scope, and known limitations.

## Known issues

- A rare search-input rollback to an earlier/default state has previously been observed and remains under investigation. It was not reproduced in the final candidate check.
- Some discussion/source targets legitimately return HTTP 404.
- Network-backed providers can rate-limit requests.
- Reference Graph enrichment is experimental.
- Back/navigation semantics, UI, iconography, accessibility, and broad device compatibility are not final.

## Diagnostics and privacy

Operational diagnostics are designed not to record query plaintext. The app separately maintains content-bearing Reference Session data for research/workflow continuity. Treat exported session data and diagnostics as user-controlled files and review them before sharing.

The app communicates with the selected external reference providers over the network. Provider-side request handling is governed by those services.

## APK distribution status

The first CodeAssist-exported APK inspected for this release was debug-signed and is retained only as an acceptance artifact. It is not the preferred public distribution artifact. A stable release-signing identity should be established before publishing an APK intended to receive future in-place updates.

## Build / source identity

Android applicationId: `com.example.quickreference`

Public versionName: `0.1.0-alpha.1`

versionCode: `18`

The exact tested `MainActivity.java` identity is recorded in `PUBLIC_RELEASE_PROVENANCE.md`.

## Feedback

Issues and workflow reports are welcome. When reporting a bug, include the app version, Android version/device, approximate reproduction steps, and sanitized diagnostics where useful. Do not post secrets, personal data, private queries/session content, or unsanitized diagnostics to a public issue.

Code contributions are not being accepted at this stage while architecture and licensing are still being evaluated.

## License / rights

This repository is **public source**, but no open-source license has been granted at this stage.

Copyright © 2026 grapefruitmio-lab. All rights reserved.

See `RIGHTS.md`.
