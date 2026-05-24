from __future__ import annotations

import json

from once.describe import (
    compute_target,
    describe as describe_step,
    describe_report,
    find_container_for_host,
    image_to_repository_tag,
    matching_repo_digest,
    once_command_check_args,
    parse_once_list,
    provider_summary,
    update_available,
)

BASE_OPTS = {
    "profile": "test",
    "params": {
        "provider-compute": "digitalocean",
        "provider-backend": "r2",
        "provider-smtp": "resend",
        "provider-dns": "cloudflare",
        "ip": "203.0.113.10",
        "user": "root",
    },
}


def ok(out=""):
    return {"ok": True, "exit": 0, "out": out, "err": ""}


def fail(err):
    return {"ok": False, "exit": 255, "out": "", "err": err}


def remote_command(args):
    idx = next(i for i, a in enumerate(args) if "@" in a)
    return args[idx + 1 :]


def identity(opts):
    return opts


def test_provider_summary_extracts_provider_names():
    assert provider_summary(BASE_OPTS["params"]) == {
        "compute": "digitalocean",
        "backend": "r2",
        "smtp": "resend",
        "dns": "cloudflare",
    }


def test_no_infra_compute_target_uses_configured_ip_and_user_when_state_missing():
    assert compute_target(
        {
            "provider-compute": "no-infra",
            "ip": "192.168.0.1",
            "no-infra-compute-ip": "10.0.0.5",
            "no-infra-compute-user": "ubuntu",
        }
    ) == {"ip": "10.0.0.5", "user": "ubuntu"}


def test_image_ref_parsing_handles_tags_defaults_and_registry_ports():
    assert image_to_repository_tag("ghcr.io/org/app:1.2.3") == {
        "repository": "ghcr.io/org/app",
        "tag": "1.2.3",
        "image": "ghcr.io/org/app:1.2.3",
    }
    assert image_to_repository_tag("ghcr.io/org/app") == {
        "repository": "ghcr.io/org/app",
        "tag": "latest",
        "image": "ghcr.io/org/app:latest",
    }
    assert image_to_repository_tag("localhost:5000/org/app") == {
        "repository": "localhost:5000/org/app",
        "tag": "latest",
        "image": "localhost:5000/org/app:latest",
    }
    assert image_to_repository_tag("localhost:5000/org/app:dev") == {
        "repository": "localhost:5000/org/app",
        "tag": "dev",
        "image": "localhost:5000/org/app:dev",
    }


def test_once_list_parsing_strips_ansi_and_reads_status():
    output = "\x1b[32mwww.example.com\x1b[0m (running)\nforms.example.com (stopped)\n"
    assert parse_once_list(output) == [
        {"host": "www.example.com", "status": "running"},
        {"host": "forms.example.com", "status": "stopped"},
    ]


def test_docker_container_matching_uses_labels_and_names():
    containers = [
        {
            "Name": "/once-www-example-com",
            "Config": {
                "Image": "ghcr.io/org/app:latest",
                "Labels": {"traefik.http.routers.app.rule": "Host(`www.example.com`)"},
            },
        },
        {"Name": "/other", "Config": {"Image": "ghcr.io/org/other:latest"}},
    ]
    assert find_container_for_host(containers, "www.example.com")["Config"]["Image"] == "ghcr.io/org/app:latest"


def test_digest_selection_and_comparison():
    assert matching_repo_digest("ghcr.io/org/app", ["ghcr.io/org/app@sha256:aaa", "ghcr.io/org/other@sha256:bbb"]) == "sha256:aaa"
    assert update_available("sha256:aaa", "sha256:aaa") is False
    assert update_available("sha256:aaa", "sha256:bbb") is True
    assert update_available(None, "sha256:bbb") is None


def test_describe_ssh_failure_soft_fails_and_skips_remote_apps():
    calls = []

    def run_fn(args, opts=None):
        calls.append(args)
        if "once" in args:
            raise AssertionError("remote apps should not be checked")
        return fail("Permission denied")

    result = describe_report(BASE_OPTS, run_fn, identity)
    assert result["compute"]["running"] is False
    assert result["applications"] == []
    assert "not checked" in result["applicationsError"]
    assert len(calls) == 1


def test_describe_remote_command_failure_keeps_compute_running():
    def run_fn(args, opts=None):
        cmd = remote_command(args)
        if cmd == ["true"]:
            return ok()
        if cmd == once_command_check_args:
            return ok()
        if cmd == ["sudo", "-n", "once", "list"]:
            return fail("once missing")
        raise AssertionError(f"unexpected command: {args!r}")

    result = describe_report(BASE_OPTS, run_fn, identity)
    assert result["compute"]["running"] is True
    assert result["applications"] == []
    assert result["fatalError"] is False
    assert "once list failed" in result["applicationsError"]


def test_describe_missing_remote_once_command_is_fatal():
    def run_fn(args, opts=None):
        cmd = remote_command(args)
        if cmd == ["true"]:
            return ok()
        if cmd == once_command_check_args:
            return {"ok": False, "exit": 127, "out": "", "err": "once: command not found"}
        raise AssertionError(f"unexpected command: {args!r}")

    result = describe_report(BASE_OPTS, run_fn, identity)
    assert result["compute"]["running"] is True
    assert result["applications"] == []
    assert result["fatalError"] is True
    assert "once command check failed" in result["applicationsError"]


def test_describe_workflow_step_sets_exit_status_soft():
    result = describe_step([], BASE_OPTS, lambda _opts: {"profile": "test", "providers": {}, "compute": {}, "applications": [], "fatalError": False})
    assert result["exit"] == 0
    assert result["describe/result"]["fatalError"] is False


def test_describe_workflow_step_sets_exit_status_fatal():
    result = describe_step([], BASE_OPTS, lambda _opts: {"profile": "test", "providers": {}, "compute": {}, "applications": [], "fatalError": True})
    assert result["exit"] == 1
    assert result["err"] == "describe failed"


def test_describe_success_reports_image_digests_and_update_status():
    container = {
        "Id": "container-1",
        "Name": "/once-www-example-com",
        "Image": "sha256:local-image",
        "Config": {
            "Image": "ghcr.io/org/app:latest",
            "Labels": {"traefik.http.routers.app.rule": "Host(`www.example.com`)"},
        },
        "State": {"Status": "running"},
    }
    image = {
        "Id": "sha256:local-image",
        "RepoTags": ["ghcr.io/org/app:latest"],
        "RepoDigests": ["ghcr.io/org/app@sha256:old"],
        "Architecture": "amd64",
        "Os": "linux",
    }

    def run_fn(args, opts=None):
        if args[0] == "skopeo":
            assert "--override-os" in args
            assert "--override-arch" in args
            assert "docker://ghcr.io/org/app:latest" in args
            return ok(json.dumps({"Digest": "sha256:new"}))
        cmd = remote_command(args)
        if cmd == ["true"]:
            return ok()
        if cmd == once_command_check_args:
            return ok()
        if cmd == ["sudo", "-n", "once", "list"]:
            return ok("www.example.com (running)\n")
        if cmd == ["sudo", "-n", "docker", "ps", "-q"]:
            return ok("container-1\n")
        if cmd == ["sudo", "-n", "docker", "inspect", "--type", "container", "container-1"]:
            return ok(json.dumps([container]))
        if cmd == ["sudo", "-n", "docker", "image", "inspect", "sha256:local-image", "ghcr.io/org/app:latest"]:
            return ok(json.dumps([image]))
        raise AssertionError(f"unexpected command: {args!r}")

    result = describe_report(BASE_OPTS, run_fn, identity)
    app = result["applications"][0]
    assert result["applicationsError"] is None
    assert app["host"] == "www.example.com"
    assert app["image"] == "ghcr.io/org/app:latest"
    assert app["version"] == "latest"
    assert app["digest"] == "sha256:old"
    assert app["registryDigest"] == "sha256:new"
    assert app["newVersion"] is True
