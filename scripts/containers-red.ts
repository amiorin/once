// Print which container red resolves for every host in the parity corpus, one
// `host=image` line per entry. Green and blue print the same shape, so parity.sh
// can diff them directly.
import { containerForHost } from "../red/src/describe.ts";

const { containers, hosts } = JSON.parse(await Bun.file(Bun.argv[2]).text());
for (const host of hosts) {
  console.log(`${host}=${containerForHost(containers, host)?.Config?.Image ?? ""}`);
}
