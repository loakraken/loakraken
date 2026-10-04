"""Validate generated OpenAPI 3.1 schemas against real test response artifacts."""

import copy
import argparse
import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--live", action="store_true", help="Validate explicit live-test artifacts without making API calls")
    args = parser.parse_args()
    generated = Path(__file__).resolve().parents[1] / "build" / "generated"
    document = load(generated / ("openapi-live.json" if args.live else "openapi.json"))
    if not document["openapi"].startswith("3.1."):
        raise ValueError("OpenAPI 3.1 is required")
    operation = document["paths"]["/api/v1/characters/search"]["get"]
    history = document["paths"]["/api/v1/characters/snapshots"]["get"] if not args.live else None
    for definition in document["components"]["schemas"].values():
        Draft202012Validator.check_schema(definition)
    cases = [
        ("character-search.json", operation["responses"]["200"]["content"]["application/json"]["schema"]),
        ("character-full.json", {"$ref": "#/components/schemas/CharacterResponse"}),
        ("invalid-request.json", operation["responses"]["400"]["content"]["application/json"]["schema"]),
        ("not-acceptable.json", operation["responses"]["406"]["content"]["application/json"]["schema"]),
    ]
    if args.live:
        cases = [("character-live.json", operation["responses"]["200"]["content"]["application/json"]["schema"])]
    else:
        for filename, status in [("snapshot-history.json", "200"), ("snapshot-history-empty.json", "200"),
                                 ("snapshot-history-invalid-cursor.json", "400"), ("snapshot-history-unavailable.json", "503")]:
            cases.append((filename, history["responses"][status]["content"]["application/json"]["schema"]))
    for filename, response_schema in cases:
        schema = {"$schema": "https://json-schema.org/draft/2020-12/schema", "components": document["components"], **response_schema}
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        value = load(generated / filename)
        validator.validate(value)
        # Prove that a missing required field is rejected, not only that valid fixtures pass.
        invalid = copy.deepcopy(value)
        invalid.pop("characterName" if filename == "character-full.json" else "code")
        if validator.is_valid(invalid):
            raise AssertionError(f"Required field is not enforced: {filename}")
        if filename == "character-search.json":
            for field, replacement in [("category", "UNSUPPORTED"), ("observedCount", "unknown")]:
                invalid = copy.deepcopy(value)
                invalid["data"]["analysis"]["diagnostics"]["findings"][0][field] = replacement
                if validator.is_valid(invalid):
                    raise AssertionError(f"Diagnostic field is not enforced: {field}")
        print(f"PASS {filename}")


if __name__ == "__main__":
    main()
