from __future__ import annotations

from blue.runtime import ExecResult

from package_once_blue.github import (
    commands,
    known_hosts_line,
    github_step,
    key_comment,
    placeholder_keys,
    publish_commands,
    revoke_commands,
)
from package_once_blue.tools import deploy_keys_content

opts = {
    "profile": "prod",
    "ip": "203.0.113.10",
    "github-token": "gh_token",
    "once": {
        "applications": [
            {"host": "www.example.com", "github": "acme/site"},
            {"host": "www.example.net"},
        ]
    },
}

key = {
    "host": "www.example.com",
    "github": "acme/site",
    "public": "ssh-ed25519 AAAA one",
    "private-file": "/tmp/once/key-0",
}


def recorder(exit_code: int = 0):
    """A fake runner in place of the runtime seam.

    Records every invocation so a test can assert the exact argv without a
    process.
    """
    calls: list[dict] = []

    async def run_fn(args, *, env=None):
        calls.append({"args": args, "env": env})
        return ExecResult(exit=exit_code, out="", err="" if exit_code == 0 else "boom")

    return calls, run_fn


def test_only_applications_naming_a_repository_are_published():
    assert len(commands({**opts, "blue/event": "create", "once/deploy-keys": [key]})) == 5


def test_publish_sends_the_address_as_a_variable_and_the_key_as_a_secret():
    environment, ip, user, known_hosts, secret = publish_commands(opts, key)
    # The environment is created first — writing into one that does not exist is
    # a 404, and nothing guarantees a workflow made it.
    assert environment["args"] == ["gh", "api", "--method", "PUT", "--silent", "repos/acme/site/environments/prod"]
    # The address and user are variables — DNS already reveals them, and masking
    # them only makes CI logs harder to read.
    assert ip["args"] == [
        "gh", "variable", "set", "SERVER_IP",
        "--repo", "acme/site", "--env", "prod",
        "--body", "203.0.113.10",
    ]
    assert user["args"] == [
        "gh", "variable", "set", "SERVER_USER",
        "--repo", "acme/site", "--env", "prod",
        "--body", "deploy",
    ]
    # The host key is pinned as a variable, so CI stops asking the network who
    # the server is on every deploy.
    assert known_hosts["args"] == [
        "gh", "variable", "set", "SSH_KNOWN_HOSTS",
        "--repo", "acme/site", "--env", "prod", "--body", "",
    ]
    # The private key is read from its file, never passed as an argument.
    assert secret["args"][:2] == ["sh", "-c"]
    assert "< '/tmp/once/key-0'" in secret["args"][2]


def test_revoking_needs_no_key_material():
    assert [cmd["args"] for cmd in revoke_commands(opts, {"github": "acme/site"})] == [
        ["gh", "variable", "delete", "SERVER_IP", "--repo", "acme/site", "--env", "prod"],
        ["gh", "variable", "delete", "SERVER_USER", "--repo", "acme/site", "--env", "prod"],
        ["gh", "variable", "delete", "SSH_KNOWN_HOSTS", "--repo", "acme/site", "--env", "prod"],
        ["gh", "secret", "delete", "SSH_PRIVATE_KEY", "--repo", "acme/site", "--env", "prod"],
    ]


async def test_a_build_never_reaches_github():
    # wire_fn runs the same branch for build and create, so the event check in
    # the step is what keeps a build offline.
    calls, run_fn = recorder()
    await github_step({**opts, "blue/event": "build"}, run_fn)
    assert calls == []


async def test_the_token_travels_in_the_environment():
    calls, run_fn = recorder()
    await github_step({**opts, "blue/event": "create", "once/deploy-keys": [key]}, run_fn)
    assert calls
    # Every gh call carries it; the host-key read is an ssh call and has no
    # business with a GitHub token.
    assert all(c["env"] == {"GH_TOKEN": "gh_token"} for c in calls if c["args"][0] == "gh")


async def test_a_failed_publish_fails_the_step():
    _calls, run_fn = recorder(1)
    result = await github_step({**opts, "blue/event": "create", "once/deploy-keys": [key]}, run_fn)
    assert result["blue/exit"] == 1
    assert "acme/site" in result["blue/err"]


async def test_a_failed_revoke_does_not():
    # Delete has to be re-runnable, and a missing secret is the state it is
    # trying to reach.
    calls, run_fn = recorder(1)
    result = await github_step({**opts, "blue/event": "delete"}, run_fn)
    assert result["blue/exit"] == 0
    assert len(calls) == 4


def test_each_key_is_pinned_to_its_own_host():
    keys = [
        {"host": "www.example.com", "public": "ssh-ed25519 AAAA one"},
        {"host": "www.example.net", "public": "ssh-ed25519 BBBB two"},
    ]
    assert deploy_keys_content({**opts, "once/deploy-keys": keys}) == (
        'restrict,command="/usr/local/bin/deploy www.example.com" ssh-ed25519 AAAA one\n'
        'restrict,command="/usr/local/bin/deploy www.example.net" ssh-ed25519 BBBB two\n'
    )


def test_a_build_renders_a_fixed_placeholder():
    # A fresh key per build would make the artifact nondeterministic and break
    # byte parity between the colours.
    first = placeholder_keys(opts)
    assert first == placeholder_keys(opts)
    assert len(first) == 1
    assert first[0]["public"].endswith("once-deploy-prod-www.example.com")


def test_the_key_comment_carries_no_clock_reading():
    assert key_comment(opts, "www.example.com") == "once-deploy-prod-www.example.com"


def test_a_host_key_becomes_a_known_hosts_line():
    # The trailing comment is the server's own hostname at key generation time
    # and means nothing to a client.
    assert known_hosts_line("203.0.113.10", "ssh-ed25519 AAAAC3Nz root@once\n") == (
        "203.0.113.10 ssh-ed25519 AAAAC3Nz"
    )
    assert known_hosts_line("203.0.113.10", "") is None
    assert known_hosts_line("203.0.113.10", "No such file or directory") is None
