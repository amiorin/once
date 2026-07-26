"""Print how blue's YAML reader typed every scalar in the parity corpus, one
`key=type:value` line per entry. Green and red print the same shape, so
parity.sh can diff them directly."""

import sys

from blue.cli import load_yaml


def describe(value: object) -> str:
    if value is None:
        return "null:"
    if isinstance(value, bool):
        return f"bool:{str(value).lower()}"
    if isinstance(value, int):
        return f"int:{value}"
    if isinstance(value, float):
        return f"float:{value}"
    if isinstance(value, str):
        return f"string:{value}"
    return f"other:{value}"


path = sys.argv[1]
with open(path) as handle:
    state = load_yaml(handle.read())
for key in sorted(state):
    print(f"{key}={describe(state[key])}")
