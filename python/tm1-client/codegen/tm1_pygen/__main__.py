import sys

from . import generate

if len(sys.argv) != 3:
    sys.exit("Usage: python -m tm1_pygen <spec.json> <skipcheck_tm1 package directory>")
for path in generate(sys.argv[1], sys.argv[2]):
    print(f"wrote {path}")
