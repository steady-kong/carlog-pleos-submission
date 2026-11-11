#!/usr/bin/env python3
"""Check documented examples and basic rejection against the published JSON Schemas."""
import copy
import json
from pathlib import Path

try:
    import jsonschema
except ImportError as exc:
    raise SystemExit("The optional jsonschema Python package is required; no packages were installed.") from exc

root = Path(__file__).resolve().parent.parent / "docs"
cases = (
    ("event.schema.json", "event-v1.json"),
    ("event-v2.schema.json", "event-v2.json"),
    ("vehicle-records-v1.schema.json", "vehicle-records-v1.json"),
)
for schema_name, example_name in cases:
    schema = json.loads((root / schema_name).read_text(encoding="utf-8"))
    example = json.loads((root / "examples" / example_name).read_text(encoding="utf-8"))
    validator_type = jsonschema.validators.validator_for(schema)
    validator_type.check_schema(schema)
    validator = validator_type(schema, format_checker=jsonschema.FormatChecker())
    validator.validate(example)
    invalid = copy.deepcopy(example)
    invalid["schemaVersion"] = -1
    if validator.is_valid(invalid):
        raise SystemExit(f"{schema_name} unexpectedly accepts an invalid schemaVersion")
    print(f"{example_name}: valid example; invalid version rejected")
