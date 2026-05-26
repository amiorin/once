from __future__ import annotations

from once.options import profile_alpha, profile_beta, profile_gamma, profile_no_infra
from once.validation import (
    credential_errors,
    provider_tools,
    schema_errors,
    ssh_agent_errors,
    tool_errors,
    validate,
    validate_report,
)

TEST_COMPUTE_PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHDKdUkY+SfRm6ttOz2EEZ2+i/zm+o1mpMOdMeGUr0t4 test@example.com"
TEST_DEPLOY_PUBKEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII1Lbgxiv2OnDKwc8Wx25SQlGyI+iY1drUii/IMZ3YSh deploy@example.com"

CREDS = {
    "compute-pubkey": TEST_COMPUTE_PUBKEY,
    "deploy-pubkey": TEST_DEPLOY_PUBKEY,
    "resend-api-key": "stub",
    "resend-password": "stub",
    "cloudflare-api-token": "stub",
    "hcloud-token": "stub",
    "hcloud-ssh-keys": "stub-key",
    "do-token": "stub",
    "digitalocean-vpc-uuid": "stub-vpc",
    "digitalocean-ssh-keys": "stub-key",
    "oci-config-file-profile": "DEFAULT",
    "oci-subnet-id": "stub-subnet",
    "oci-compartment-id": "stub-compartment",
    "oci-availability-domain": "stub-ad",
    "oci-ssh-authorized-keys": "~/.ssh/id_ed25519.pub",
    "no-infra-compute-ip": "192.0.2.10",
    "no-infra-compute-user": "ubuntu",
    "no-infra-compute-sudoer": "ubuntu",
    "no-infra-compute-uid": "1000",
    "no-infra-compute-name": "once",
    "no-infra-smtp-password": "stub",
    "r2-bucket": "stub-bucket",
    "r2-endpoint": "https://stub.r2.cloudflarestorage.com",
    "r2-access-key-id": "stub",
    "r2-secret-access-key": "stub",
    "s3-bucket": "stub-bucket",
    "s3-region": "eu-west-1",
}


def with_creds(profile):
    return {**profile, "params": {**profile["params"], **CREDS}}


def with_params(profile, overrides):
    return {**profile, "params": {**profile["params"], **overrides}}


def test_public_profiles_pass_schema_with_stub_creds():
    for profile in [profile_alpha, profile_beta, profile_gamma, profile_no_infra]:
        assert schema_errors(with_creds(profile)) is None


def test_placeholder_credential_is_reported():
    errors = schema_errors(profile_alpha)
    assert errors is not None
    assert any("resend-api-key" in e["detail"] and "REPLACE_ME" in e["detail"] for e in errors)


def test_validate_report_honors_bc_par_overrides_when_profile_has_aliases(monkeypatch):
    monkeypatch.setattr("once.validation.tool_errors", lambda _params: [])
    monkeypatch.setattr("once.validation.credential_errors", lambda _params, _env=None: [])
    monkeypatch.setattr("once.validation.image_errors", lambda _params: [])
    env = {f"BC_PAR_{k.upper().replace('-', '_')}": str(v) for k, v in CREDS.items()}

    assert validate_report(profile_alpha, env) == {"ok": True, "errors": []}


def test_missing_compute_pubkey_is_reported():
    p = with_creds(profile_alpha)
    params = dict(p["params"])
    del params["compute-pubkey"]
    errors = schema_errors({**p, "params": params})
    assert errors is not None
    assert any("compute-pubkey" in e["detail"] for e in errors)


def test_bad_domain_format_is_reported():
    bad = with_params(with_creds(profile_alpha), {"domain": "not_a_domain", "once": {"applications": []}})
    errors = schema_errors(bad)
    assert errors is not None
    assert any("domain" in e["detail"] and "valid domain" in e["detail"] for e in errors)


def test_cross_field_mismatched_host_is_reported():
    bad = with_params(
        with_creds(profile_alpha),
        {"once": {"applications": [{"host": "alien.example.com", "image": "ghcr.io/foo/bar:latest"}]}},
    )
    errors = schema_errors(bad)
    assert errors is not None
    assert any("subdomain" in e["detail"] for e in errors)


def test_cross_field_apex_and_subdomain_pass():
    ok_profile = with_params(
        with_creds(profile_alpha),
        {
            "once": {
                "applications": [
                    {"host": "alpha.example.com", "image": "ghcr.io/foo/bar:latest"},
                    {"host": "www.alpha.example.com", "image": "ghcr.io/foo/bar:latest"},
                ]
            }
        },
    )
    assert schema_errors(ok_profile) is None


def test_validate_workflow_step_sets_exit_status_success():
    result = validate([], {}, lambda _opts: {"ok": True, "errors": []})
    assert result["exit"] == 0
    assert result["validation/result"] == {"ok": True, "errors": []}


def test_validate_workflow_step_sets_exit_status_failure():
    result = validate([], {}, lambda _opts: {"ok": False, "errors": [{"check": "schema", "detail": "bad"}]})
    assert result["exit"] == 1
    assert result["err"] == "validation failed"


def cmds(params):
    return {t["cmd"] for t in provider_tools(params)}


def test_provider_tools_picks_the_right_clis():
    assert cmds({"provider-compute": "hcloud", "provider-backend": "s3"}) == {"hcloud", "aws"}
    assert cmds({"provider-compute": "oci", "provider-backend": "s3"}) == {"oci", "aws"}
    assert cmds({"provider-compute": "digitalocean", "provider-backend": "s3"}) == {"doctl", "aws"}
    assert cmds({"provider-compute": "hcloud", "provider-backend": "r2"}) == {"hcloud", "aws"}
    assert cmds({"provider-compute": "oci", "provider-backend": "r2"}) == {"oci", "aws"}
    assert cmds({"provider-compute": "no-infra", "provider-backend": "local"}) == set()
    assert cmds({"provider-compute": "hcloud", "provider-backend": "local"}) == {"hcloud"}


def test_tool_errors_honors_the_injected_which_fn():
    params = with_creds(profile_alpha)["params"]
    errors = tool_errors(params, lambda cmd: cmd != "tofu")
    assert len(errors) == 1
    assert "OpenTofu" in errors[0]["detail"]


def test_ssh_agent_missing_sock_reported_for_cloud_compute():
    params = {"provider-compute": "hcloud", "compute-pubkey": TEST_COMPUTE_PUBKEY}
    errors = ssh_agent_errors(params, {})
    assert len(errors) == 1
    assert "SSH_AUTH_SOCK" in errors[0]


def test_ssh_agent_no_infra_skips_check():
    params = {"provider-compute": "no-infra", "compute-pubkey": TEST_COMPUTE_PUBKEY}
    assert ssh_agent_errors(params, {}) == []


def test_ssh_agent_loaded_key_matched_ignoring_comments():
    params = {"provider-compute": "hcloud", "compute-pubkey": TEST_COMPUTE_PUBKEY}
    key_id_line = " ".join(TEST_COMPUTE_PUBKEY.split()[:2])

    def run_fn(args, extra_env=None):
        assert args == ["ssh-add", "-L"]
        assert extra_env == {"SSH_AUTH_SOCK": "/tmp/agent.sock"}
        return {"ok": True, "exit": 0, "out": f"{key_id_line} other-comment\n", "err": ""}

    assert ssh_agent_errors(params, {"SSH_AUTH_SOCK": "/tmp/agent.sock"}, run_fn) == []


def test_ssh_agent_missing_loaded_key_reported():
    params = {"provider-compute": "hcloud", "compute-pubkey": TEST_COMPUTE_PUBKEY}

    def run_fn(args, extra_env=None):
        return {"ok": True, "exit": 0, "out": "ssh-ed25519 AAAAother comment\n", "err": ""}

    errors = ssh_agent_errors(params, {"SSH_AUTH_SOCK": "/tmp/agent.sock"}, run_fn)
    assert len(errors) == 1
    assert "not loaded" in errors[0]


def test_ssh_agent_dead_sock_reported():
    params = {"provider-compute": "hcloud", "compute-pubkey": TEST_COMPUTE_PUBKEY}

    def run_fn(args, extra_env=None):
        return {"ok": False, "exit": 2, "out": "", "err": "Error connecting to agent: No such file or directory"}

    errors = ssh_agent_errors(params, {"SSH_AUTH_SOCK": "/tmp/dead.sock"}, run_fn)
    assert len(errors) == 1
    assert "ssh-add -L failed" in errors[0]


def cf_params():
    p = with_creds(profile_alpha)["params"]
    return {"provider-dns": p["provider-dns"], "domain": p["domain"], "cloudflare-api-token": p["cloudflare-api-token"]}


def test_cloudflare_zone_checks_configured_domain_exists():
    def run_fn(args, extra_env=None):
        assert any("name=alpha.example.com" in a for a in args)
        return {"ok": True, "exit": 0, "out": '{"success":true,"result":[{"id":"zone-id"}]}', "err": ""}

    assert credential_errors(cf_params(), {}, run_fn) == []


def test_cloudflare_zone_missing_reported():
    def run_fn(args, extra_env=None):
        return {"ok": True, "exit": 0, "out": '{"success":true,"result":[]}', "err": ""}

    errors = credential_errors(cf_params(), {}, run_fn)
    assert len(errors) == 1
    assert "Cloudflare zone" in errors[0]["detail"]
    assert "alpha.example.com" in errors[0]["detail"]


def test_domain_regex_table_valid():
    for domain in ["example.com", "foo.bar.example.com", "a.b", "ex-ample.co"]:
        assert schema_errors(with_params(with_creds(profile_alpha), {"domain": domain, "once": {"applications": []}})) is None


def test_domain_regex_table_invalid():
    for domain in ["not_a_domain", "", "no-dot", "UPPER.case", ".leading"]:
        assert schema_errors(with_params(with_creds(profile_alpha), {"domain": domain, "once": {"applications": []}})) is not None


def test_image_regex_table_valid():
    for image in [
        "ghcr.io/foo/bar",
        "ghcr.io/foo/bar:latest",
        "ghcr.io/org/path/sub:tag-1.2",
        "registry.example.com/foo/bar:v1",
    ]:
        assert (
            schema_errors(
                with_params(with_creds(profile_alpha), {"once": {"applications": [{"host": "www.alpha.example.com", "image": image}]}})
            )
            is None
        )


def test_image_regex_table_invalid():
    for image in ["nginx", "", "/no-registry", "Foo/Bar", "ghcr.io/foo/bar:bad tag"]:
        assert (
            schema_errors(
                with_params(with_creds(profile_alpha), {"once": {"applications": [{"host": "www.alpha.example.com", "image": image}]}})
            )
            is not None
        )
