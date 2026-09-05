# Drive the SSH Keypair Standard's create matrix, provider preflight, and
# delete cleanup through blue with injected state, keygen, and account-key
# functions, printing one normalized `case exit=<n> err=<message>` line per
# scenario. Green and red print the same shape, so parity.sh can diff them:
# none of this logic reaches a build artifact, and the error messages are
# user-facing contract.
import asyncio
import os
import tempfile
from pathlib import Path

from package_once_blue import ssh


def tmp_dir() -> str:
    """A fresh scenario directory, installed as $HOME so the keypair lands
    under it — no scenario may touch the real ~/.ssh."""
    dir = tempfile.mkdtemp(prefix="once-ssh-parity")
    os.environ["HOME"] = dir
    return dir


class _Result:
    def __init__(self, exit: int):
        self.exit = exit
        self.err = ""


async def fake_keygen(args, **_kwargs):
    path = Path(str(args[-1]))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("PRIVATE")
    Path(f"{path}.pub").write_text("ssh-ed25519 AAAATESTKEY parity managed by Colors")
    return _Result(0)


def seed(dir: str) -> None:
    asyncio.run(fake_keygen(["ssh-keygen", str(Path(dir) / ".ssh" / "parity")]))


def base(dir: str) -> dict:
    return {
        "profile": "parity",
        "provider-compute": "digitalocean",
        "do-token": "tok",
        "blue/state-file": str(Path(dir) / "colors.yml"),
    }


def line(case_name: str, dir: str, opts: dict) -> None:
    err = str(opts.get("blue/err") or "").replace(dir, "<dir>")
    print(f"{case_name} exit={opts.get('blue/exit') or 0} err={err}")


async def state_none(_opts=None):
    return None


async def state_live(_opts=None):
    return {"ip": "1.2.3.4"}


async def state_owned(_opts=None):
    return {"ip": "1.2.3.4", "ssh_key_id": "77"}


def ensure(dir: str, state_fn) -> dict:
    return asyncio.run(ssh.ensure_key(base(dir), state_fn, fake_keygen))


def pre(dir: str, state_fn, fetch_fn) -> dict:
    opts = {**ssh.with_machine_key(base(dir), True), "once/ssh-state-params": asyncio.run(state_fn())}
    return ssh.preflight(opts, fetch_fn)


def raise_api_error(_provider, _token):
    raise RuntimeError("HTTP 500 from provider")


dir = tmp_dir()
line("first-create", dir, ensure(dir, state_none))

dir = tmp_dir()
line("lost-key", dir, ensure(dir, state_live))

dir = tmp_dir()
seed(dir)
line("leftover", dir, ensure(dir, state_none))

dir = tmp_dir()
seed(dir)
line("converge", dir, ensure(dir, state_live))

dir = tmp_dir()
(Path(dir) / ".ssh").mkdir(parents=True)
(Path(dir) / ".ssh" / "parity").write_text("PRIVATE")
line("half-keypair", dir, ensure(dir, state_live))

dir = tmp_dir()
seed(dir)
line("preflight-none", dir, pre(dir, state_none, lambda _p, _t: []))

dir = tmp_dir()
seed(dir)
line("preflight-owned", dir, pre(dir, state_owned, lambda _p, _t: [{"id": "77", "name": "parity", "public": "ssh-ed25519 AAAATESTKEY"}]))

dir = tmp_dir()
seed(dir)
line("preflight-ours", dir, pre(dir, state_none, lambda _p, _t: [{"id": "77", "name": "parity", "public": "ssh-ed25519 AAAATESTKEY"}]))

dir = tmp_dir()
seed(dir)
line("preflight-foreign", dir, pre(dir, state_none, lambda _p, _t: [{"id": "88", "name": "parity", "public": "ssh-ed25519 AAAAOTHER"}]))

dir = tmp_dir()
seed(dir)
line("preflight-api-error", dir, pre(dir, state_none, raise_api_error))

dir = tmp_dir()
seed(dir)
out = ssh.cleanup_step({**base(dir), "blue/event": "delete"})
removed = not (Path(dir) / ".ssh" / "parity").exists()
print(f"cleanup exit={out.get('blue/exit') or 0} removed={'true' if removed else 'false'}")

# A key that survives the removal (read-only ~/.ssh) fails the delete with
# one message in every colour.
dir = tmp_dir()
seed(dir)
os.chmod(Path(dir) / ".ssh", 0o500)
line("cleanup-readonly", dir, ssh.cleanup_step({**base(dir), "blue/event": "delete"}))
os.chmod(Path(dir) / ".ssh", 0o700)
