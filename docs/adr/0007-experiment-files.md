# 0007. Experiment files: exact round trip, strict reader

Status: accepted

## Decision
- Format `chaoslab-experiment`, version 1: title, note, G, softening, integrator label and step size, and for each body mass,
  position[3], velocity[3]. The start time is always 0.
- Numbers are written as the shortest decimal that reads back as the same double (`ScenarioDraft.format`), so save then load
  reproduces the run bit for bit. This is tested on all five experiment classes.
- The JSON reader is strict RFC 8259: no trailing commas, no leading zeros, no duplicate keys, no control characters, depth at most
  64, file size at most 1,000,000 bytes, strict UTF-8; a leading byte-order mark is accepted. Errors give line and column.
- A file with a newer version is refused. Unknown extra keys are ignored. An unknown integrator label keeps the current integrator
  and says so.
- Files are written to a temporary file and then moved into place, so a failed write cannot leave half a file.
- CSV exports store the worst value per time bucket, as plotted; an unknown uncertainty is an empty field.

## Consequences
The five failure paths (syntax, newer version, invalid physics, unknown integrator, not JSON) were each exercised in the running
window and gave the messages the tests expect. One defect was found there: a byte-order mark was counted as a column in line-1
errors; it was fixed and tested.
