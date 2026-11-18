# Historical LocationData changes

LocationData now has its own Git repository at
`/home/ian/AndroidStudioProjects/LocationData`, initialized on 2026-11-18.
Its current source is versioned there. The patches in this directory preserve
the earlier collection changes and their application order:

1. `locationdata-independent-radio.patch`: independent radio sampling,
   schema-7 references and collection diagnostics.
2. `locationdata-session-inputs.patch`: automatic session metadata and simpler
   indoor labels.

`locationdata-session-inputs-base.sha256` records the existing files before
the second patch, after the first patch was applied. Files introduced by the
second patch have no baseline entry.

On 2026-11-18, both patches were reversed and reapplied in a temporary copy.
The resulting 14 affected files matched the current LocationData source
byte for byte. LocationData's new Git ignore rules are separate from these
historical patches. Captures, local properties and build output are excluded
from both repositories.
