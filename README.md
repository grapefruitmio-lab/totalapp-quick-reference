# Quick Reference

Quick Reference is an experimental Android-first reference workspace for moving quickly from selected text to multiple reference sources, documents, language variants, and related references without turning every lookup into a browser-tab workflow.

**Public release target:** `0.1.0-alpha.1`  
**Internal tested lineage:** `v0.1q-workspace-transaction` / versionCode 18

## Status

Experimental public alpha. This is an early research/development build, not a stable release.

The current build has been dogfooded on a physical Android 14 device. The deterministic in-app smoke scenario currently reports 14/14 assertions passing. Real-device testing has also exercised persisted navigation/workspace state, the explicit Working Stack, Wikimedia representation traversal, language traversal, and Reference Graph enrichment.

## What it does

- Accepts text through Android `PROCESS_TEXT` and `ACTION_SEND`.
- Searches Wikipedia, Wiktionary, Wikidata, Wikisource, and OpenAlex.
- Keeps a persistent navigation/workspace history so lookup is not reduced to one disposable page.
- Provides a small explicit Working Stack for references the user deliberately pins.
- Traverses Wikipedia article, history, discussion, and language representations.
- Enriches Wikipedia references through Wikidata sitelinks to expose additional language/reference possibilities.
- Records privacy-bounded operational diagnostics separately from content-bearing Reference Session events.
- Includes a deterministic smoke scenario for state/race/backoff invariants.

## Known issues

- Rarely, the search input field can revert to a previous/default state during some navigation or lifecycle sequences. This has been observed on a physical device and remains under investigation.
- The Working Stack is intentionally limited to 8 items; a 9th pin is rejected rather than silently deleting an older pin.
- Some Wikipedia discussion pages legitimately do not exist (HTTP 404).
- Wikimedia/OpenAlex requests can be rate-limited; the app applies basic cooldown/backoff but network-dependent operations can still fail.
- Language/reference discovery is not yet progressively rendered while enrichment is running.
- The Reference Graph is still an experimental enrichment layer rather than a persistent first-class graph database.
- UI, iconography, accessibility, documentation, and navigation semantics are still evolving.
- Testing is currently limited; broad device/Android-version compatibility is not claimed.

Please report reproducible failures with the app version, Android version/device, approximate steps, and sanitized diagnostics when possible. Do not post private query/session content publicly unless you intend to share it.

## Privacy and diagnostics

Operational diagnostics are designed not to store query plaintext. Content-bearing Reference Session data is a separate surface and should be treated as potentially private. Before attaching logs to a public issue, review them and remove anything you do not want to publish.

The app uses network services from its configured reference providers. Their own terms and privacy policies apply to requests sent to those services.

## Source and licensing

The source is publicly visible for inspection as part of this experimental alpha. **No open-source license is granted at this stage. All rights reserved.**

Unless a separate license is explicitly provided, permission is not granted to copy, modify, redistribute, sublicense, or commercially reuse the source code beyond rights that may apply independently under law or the hosting platform's terms.

Licensing may be reconsidered as the project matures.

Code contributions are not being accepted yet. Bug reports, usage observations, and design feedback are welcome after the public feedback surfaces are opened.

Copyright © 2026 grapefruitmio-lab. All rights reserved.

## Development note

Quick Reference started as a deliberately small lookup surface and has evolved into an experiment in mobile-first reference work: query state, reference identity, navigation history, explicit working sets, provenance, and an available/visited reference graph are treated as distinct objects rather than being collapsed into browser history.

The broader research direction is a provider-neutral Reference Workspace in which human and AI work can share inspectable reference/evidence/provenance state without silently replacing factual provenance.
