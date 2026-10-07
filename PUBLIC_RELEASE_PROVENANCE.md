# Public release provenance

Release target: `0.1.0-alpha.1`

Internal tested lineage: `v0.1q-workspace-transaction` / versionCode 18.

The public-alpha source was prepared from the physical-device-tested v0.1q source snapshot. Runtime implementation files are preserved from that tested snapshot; the intended release delta is limited to public release metadata and public repository documentation.

Verified implementation identity:

- `app/src/main/java/com/example/quickreference/MainActivity.java`
  - size: 86,789 bytes
  - SHA-256: `d01cd5a51a5764a42e914dd875b7b2521d7e0d67513f4a6bc8b42dda8b123df4`
  - Git blob SHA-1: `6ba55fe4223c5ab0ea84f2b1ea5d72fb0583f924`
- `app/src/main/java/com/example/quickreference/ReferenceSessionStore.java`
  - Git blob SHA-1: `bc93f4327546afd0545303951b5a8d28dcf38abb`

Deliberate public-release metadata delta:

- `app/build.gradle`: `versionName` changed from `0.1q-workspace-transaction` to `0.1.0-alpha.1`; versionCode remains 18.
- Public README, rights notice, security/privacy reporting guidance, and this provenance note are added/updated for the alpha publication.

The hard-coded diagnostic lineage string inside `MainActivity.java` remains `0.1q-workspace-transaction` in this alpha so the tested implementation file remains byte-identical to the device-tested source. The Android package versionName is `0.1.0-alpha.1`.

Known issues and testing scope are documented in `README.md`.
