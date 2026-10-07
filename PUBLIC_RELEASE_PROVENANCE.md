# Public Release Provenance — Quick Reference 0.1.0-alpha.1

This repository was prepared from the tested internal lineage `v0.1q-workspace-transaction`.

## Public identity

- Public version: `0.1.0-alpha.1`
- Android applicationId: `com.example.quickreference`
- versionCode: `18`
- Tested internal lineage: `v0.1q-workspace-transaction`

## Tested implementation identity

`MainActivity.java`

- Size: `86,789 bytes`
- Git blob SHA-1: `6ba55fe4223c5ab0ea84f2b1ea5d72fb0583f924`
- SHA-256: `d01cd5a51a5764a42e914dd875b7b2521d7e0d67513f4a6bc8b42dda8b123df4`

The public source differs intentionally from the tested internal source only in release-facing metadata/documentation, including `versionName = 0.1.0-alpha.1`.

## Acceptance evidence

The public-alpha candidate source was built and installed on a physical Android 14 device and exercised through the documented smoke/acceptance flows. The deterministic in-app smoke suite reported 14/14 passing assertions during the final candidate check.

## APK status

The first APK exported from CodeAssist for final inspection is a **debug-signed acceptance artifact**, not the preferred public distribution artifact. It is intentionally not committed to source control. A stable release-signing identity should be established before publishing a downloadable APK intended to receive future in-place updates.
