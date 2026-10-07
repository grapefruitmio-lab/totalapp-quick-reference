# Reference event schema 0.1

Quick Reference keeps research provenance separate from privacy-bounded diagnostics.

Each session is append-only JSONL. Minimum event envelope:

- schema: quickreference.reference-event/0.1
- session_id
- event_id
- timestamp
- app_version
- event_type
- provenance

Initial provenance value is USER_ACTION. The model reserves distinct provenance classes for OBSERVED_SOURCE, API_RELATION, USER_CREATED, AI_DERIVED and OUTPUT_USED.

Initial event types implemented in v0.1e include SESSION_START, QUERY, TRAVERSE and NODE_OPEN.

This store is content-bearing by design and must not be conflated with diagnostics. Future UI/export controls must make that distinction explicit.
