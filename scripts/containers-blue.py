# Print which container blue resolves for every host in the parity corpus, one
# `host=image` line per entry. Green and red print the same shape, so parity.sh
# can diff them directly.
import json
import sys

from package_once_blue.describe import _container_for_host

fixture = json.loads(open(sys.argv[1]).read())
for host in fixture["hosts"]:
    container = _container_for_host(fixture["containers"], host) or {}
    print(f"{host}={container.get('Config', {}).get('Image', '')}")
