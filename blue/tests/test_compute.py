import re
from pathlib import Path

import pytest
from blue.workflow import StepError

from package_once_blue import compute as sut

# A two-provider stub registry shaped like clickstack's: the package-owned
# data ONCE takes as a spec value. The same stub drives green and red.
registry = {
    "vultr": {
        "required": ["vultr-region", "vultr-plan", "vultr-os-id", "vultr-ssh-sources", "vultr-http-sources"],
        "secrets": ["vultr-api-key"],
        "tofu-env": {"vultr-api-key": "VULTR_API_KEY"},
    },
    "digitalocean": {
        "required": ["digitalocean-region", "digitalocean-size", "digitalocean-image",
                     "digitalocean-ssh-sources", "digitalocean-http-sources"],
        "secrets": ["do-token"],
        "tofu-env": {"do-token": "DIGITALOCEAN_TOKEN"},
    },
}

spec = {
    "registry": registry,
    "default": "vultr",
    "sources": {"non_empty": ["ssh-sources"], "may_be_empty": ["http-sources"]},
}


def vultr(**kvs):
    return {"profile": "prod", "provider-compute": "vultr", **kvs}


def digitalocean(**kvs):
    return {"profile": "prod", "provider-compute": "digitalocean", **kvs}


selection_message = ":provider-compute must be one of digitalocean, vultr"


def test_selection_refuses_an_unadvertised_provider_with_the_sorted_list():
    assert sut.provider(spec, {"provider-compute": "hetzner"}) is None
    assert sut.selection_errors(spec, {"provider-compute": "hetzner"}) == [selection_message]
    assert sut.selection_errors(spec, {}) == [selection_message]
    assert sut.selection_errors(spec, vultr()) == []
    # An unselected provider reports the selection alone; its keys are not checked.
    assert sut.state_errors(spec, {"provider-compute": "hetzner", "hetzner-ssh-sources": ["nope"],
                                   "hetzner-name": "BAD NAME"}) == [selection_message]


def test_selection_ignores_keys_of_the_unselected_provider():
    assert sut.state_errors(spec, vultr(**{
        "vultr-ssh-sources": ["10.0.0.0/8"], "vultr-os-id": 2284,
        "digitalocean-ssh-sources": ["nope"], "digitalocean-vpc-uuid": "vpc-123",
        "digitalocean-name": "BAD NAME"})) == []


def test_required_keys_secrets_and_tofu_env_follow_the_selected_entry():
    assert sut.required_keys(spec, vultr()) == registry["vultr"]["required"]
    assert sut.required_keys(spec, digitalocean()) == registry["digitalocean"]["required"]
    assert sut.required_keys(spec, {"provider-compute": "hetzner"}) == []
    assert sut.secrets(spec, vultr()) == ["vultr-api-key"]
    assert sut.secrets(spec, digitalocean()) == ["do-token"]
    assert sut.secrets(spec, {}) == []
    assert sut.tofu_env(spec, vultr()) == {"vultr-api-key": "VULTR_API_KEY"}
    assert sut.tofu_env(spec, digitalocean()) == {"do-token": "DIGITALOCEAN_TOKEN"}
    assert sut.tofu_env(spec, {}) == {}


def test_compute_key_and_name_follow_the_selected_provider():
    assert sut.compute_key(vultr(), "ssh-sources") == "vultr-ssh-sources"
    assert sut.compute_key(digitalocean(), "name") == "digitalocean-name"
    assert sut.compute_name(vultr()) == "prod"
    assert sut.compute_name(vultr(**{"vultr-name": "box"})) == "box"
    assert sut.compute_name(vultr(**{"vultr-name": " box "})) == "box"
    assert sut.compute_name(vultr(**{"vultr-name": "REPLACE_ME"})) == "prod"
    assert sut.compute_name(vultr(**{"vultr-name": ""})) == "prod"
    assert sut.compute_name(vultr(**{"digitalocean-name": "other"})) == "prod"


def test_cidr_grammar():
    # v4
    assert sut.cidr("10.0.0.0/8")
    assert sut.cidr("203.0.113.7/32")
    assert sut.cidr("0.0.0.0/0")
    # v6
    assert sut.cidr("2001:db8::/32")
    assert sut.cidr("::1/128")
    assert sut.cidr("1:2:3:4:5:6:7:8/128")
    # ::
    assert sut.cidr("::/0")
    # v4-tail
    assert sut.cidr("::ffff:203.0.113.7/128")
    assert sut.cidr("64:ff9b::192.0.2.33/96")
    assert not sut.cidr("::ffff:300.0.0.1/128")
    assert not sut.cidr("192.0.2.1::/96")
    # bad prefix
    assert not sut.cidr("10.0.0.0/33")
    assert not sut.cidr("2001:db8::/129")
    assert not sut.cidr("10.0.0.0/")
    assert not sut.cidr("10.0.0.0")
    assert not sut.cidr("10.0.0.0/8/8")
    # bad octet and bad groups
    assert not sut.cidr("256.0.0.1/8")
    assert not sut.cidr("2001:db8:::1/64")
    assert not sut.cidr("1:2:3:4:5:6:7:8:9/64")
    assert not sut.cidr("2001:db8::g/64")
    # hostname
    assert not sut.cidr("example.com/32")
    # blank
    assert not sut.cidr("")
    assert not sut.cidr(None)


def test_source_errors_follow_the_spec():
    # an empty non_empty key is refused
    assert sut.source_errors(spec, vultr(**{"vultr-ssh-sources": []})) == [":vultr-ssh-sources must list at least one CIDR"]
    # an empty may_be_empty key is allowed
    assert sut.source_errors(spec, vultr(**{"vultr-ssh-sources": ["10.0.0.0/8"], "vultr-http-sources": []})) == []
    # malformed entries are counted per key
    assert sut.source_errors(spec, vultr(**{"vultr-ssh-sources": ["10.0.0.0/8", "nope"],
                                            "vultr-http-sources": ["::1/129", "1.2.3.4/32"]})) == [
        ':vultr-ssh-sources entry "nope" is not an IPv4 or IPv6 CIDR',
        ':vultr-http-sources entry "::1/129" is not an IPv4 or IPv6 CIDR',
    ]
    # an overlay string parses
    assert sut.cidrs(vultr(**{"vultr-ssh-sources": "10.0.0.0/8, 192.0.2.0/24"}), "vultr-ssh-sources") == ["10.0.0.0/8", "192.0.2.0/24"]
    assert sut.source_errors(spec, vultr(**{"vultr-ssh-sources": "10.0.0.0/8, 192.0.2.0/24"})) == []
    assert sut.source_errors(spec, vultr(**{"vultr-ssh-sources": "10.0.0.0/8 bad"})) == [
        ':vultr-ssh-sources entry "bad" is not an IPv4 or IPv6 CIDR']
    # an absent key is skipped — presence is required_keys' job
    assert sut.source_errors(spec, vultr()) == []
    assert sut.source_errors(spec, vultr(**{"vultr-ssh-sources": "  "})) == []
    # the spec decides which suffixes exist
    three = {**spec, "sources": {"non_empty": ["ssh-sources"], "may_be_empty": ["http-sources", "stun-sources"]}}
    assert sut.source_errors(three, vultr(**{"vultr-stun-sources": ["x"]})) == [
        ':vultr-stun-sources entry "x" is not an IPv4 or IPv6 CIDR']
    assert sut.source_errors(spec, vultr(**{"vultr-stun-sources": ["x"]})) == []


do_name_message = ":digitalocean-name must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"
do_profile_message = ":profile (the digitalocean machine name) must be a hostname-like name: lowercase letters, digits, dots and hyphens, 1-63 characters"


def test_provider_errors_check_the_resolved_name_and_the_provider_rules():
    # default name rules on the raw override, blamed on the override key
    assert sut.provider_errors(spec, vultr(**{"vultr-name": "bad name!"})) == [":vultr-name must be a safe 1-63 character name"]
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-name": "Upper"})) == [do_name_message]
    # default name rules on the resolved profile, blamed on the profile
    assert sut.provider_errors(spec, vultr(profile="bad name!")) == [":profile (the vultr machine name) must be a safe 1-63 character name"]
    assert sut.provider_errors(spec, digitalocean(profile="under_score")) == [do_profile_message]
    assert sut.provider_errors(spec, digitalocean(profile="Bad", **{"digitalocean-name": "REPLACE_ME"})) == [do_profile_message]
    # length and the valid shapes
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-name": "a" * 64})) == [do_name_message]
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-name": "a" * 63})) == []
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-name": "prod-1.example"})) == []
    assert sut.provider_errors(spec, vultr(**{"vultr-name": "Prod_1"})) == []
    assert sut.provider_errors(spec, vultr(**{"vultr-name": " Prod_1 "})) == []
    assert sut.provider_errors(spec, digitalocean(profile="")) == []
    # a spec-supplied rule set wins
    own = {**spec, "name_rules": {"vultr": {"re": re.compile(r"x"), "message": "must be x"}}}
    assert sut.provider_errors(own, vultr(**{"vultr-name": "prod"})) == [":vultr-name must be x"]
    assert sut.provider_errors(own, digitalocean(**{"digitalocean-name": "Upper"})) == []
    # Vultr os-id
    assert sut.provider_errors(spec, vultr(**{"vultr-os-id": "2284"})) == [":vultr-os-id must be Vultr's numeric operating-system id"]
    assert sut.provider_errors(spec, vultr(**{"vultr-os-id": 2284})) == []
    assert sut.provider_errors(spec, vultr(**{"vultr-os-id": True})) == [":vultr-os-id must be Vultr's numeric operating-system id"]
    assert sut.provider_errors(spec, vultr()) == []
    # DigitalOcean vpc bans
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123"})) == [
        ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime"]
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-vpc-cidr": "10.0.0.0/16"})) == [
        ":digitalocean-vpc-cidr must be absent; this package must not create a VPC"]
    assert sut.provider_errors(spec, digitalocean(**{"digitalocean-vpc-uuid": "vpc-123", "digitalocean-vpc-cidr": "10.0.0.0/16"})) == [
        ":digitalocean-vpc-uuid must be absent; the default regional VPC is discovered at runtime",
        ":digitalocean-vpc-cidr must be absent; this package must not create a VPC",
    ]
    # nothing when the other provider is selected
    assert sut.provider_errors(spec, vultr(**{"digitalocean-vpc-uuid": "vpc-123", "digitalocean-name": "BAD NAME"})) == []
    assert sut.provider_errors(spec, digitalocean(**{"vultr-os-id": "2284", "vultr-name": "bad name!"})) == []


def test_state_errors_order_selection_then_source_then_provider():
    assert sut.state_errors(spec, digitalocean(**{"digitalocean-ssh-sources": ["nope"], "digitalocean-name": "Upper"})) == [
        ':digitalocean-ssh-sources entry "nope" is not an IPv4 or IPv6 CIDR', do_name_message]


legacy_message = ("state holds a machine with no recorded provider, created before this package recorded one, "
                  "which makes it a vultr machine; set provider-compute back to vultr and delete first")


def test_provider_state_errors_implement_the_switch_and_legacy_rules():
    # nil params
    assert sut.provider_state_errors(spec, vultr(), None) == []
    # match
    assert sut.provider_state_errors(spec, vultr(), {"provider": "vultr", "ip": "1.2.3.4"}) == []
    assert sut.provider_state_errors(spec, digitalocean(), {"provider": "digitalocean"}) == []
    # mismatch both ways
    assert sut.provider_state_errors(spec, vultr(), {"provider": "digitalocean"}) == [
        "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
    assert sut.provider_state_errors(spec, digitalocean(), {"provider": "vultr"}) == [
        "state holds a vultr machine; set provider-compute back to vultr and delete first"]
    # legacy on the default
    assert sut.provider_state_errors(spec, vultr(), {"ip": "1.2.3.4"}) == []
    # legacy on a non-default
    assert sut.provider_state_errors(spec, digitalocean(), {"ip": "1.2.3.4"}) == [legacy_message]
    assert sut.provider_state_errors(spec, digitalocean(), {"provider": ""}) == [legacy_message]


def test_resolved_compute_refuses_a_missing_ip():
    fallback = sut.fallback_params(vultr())
    # missing ip refuses
    refused = sut.resolved_compute({"a": 1}, fallback, None)
    assert refused["blue/exit"] == 1
    assert refused["blue/err"] == "compute produced no ip output; refusing to converge against the documentation address"
    assert sut.resolved_compute({}, fallback, {"name": "prod"})["blue/exit"] == 1
    # present ip merges outputs over the fallback
    assert sut.resolved_compute({"a": 1}, fallback, {"ip": "1.2.3.4", "name": "box"}) == {
        "a": 1, "provider": "vultr", "ip": "1.2.3.4", "user": "root", "sudoer": "root", "name": "box"}
    # output_params leaves the map alone
    assert sut.output_params({"tofu/outputs": {"params": {"ip": "1.2.3.4", "ssh_key_id": "77"}}}) == {"ip": "1.2.3.4", "ssh_key_id": "77"}
    assert sut.output_params({"tofu/outputs": {}}) is None
    assert sut.output_params({}) is None


async def test_read_state_catches_only_the_step_error():
    async def step_error(_opts):
        raise StepError("tofu output failed: boom")

    async def step_error_without_message(_opts):
        raise StepError("")

    async def nothing(_opts):
        return None

    async def params(opts):
        return {"ip": "1.2.3.4", "seen": opts.get("profile")}

    async def defect(_opts):
        raise RuntimeError("defect")

    async def type_defect(_opts):
        raise TypeError("not a step error")

    # the reader's step error becomes error
    assert await sut.read_state({}, step_error) == {"error": "tofu output failed: boom"}
    # a step error without a message reads as the fixed fallback string
    assert await sut.read_state({}, step_error_without_message) == {"error": "state read failed without a message"}
    # None from the reader is a readable state holding nothing
    async def launch_failure(_opts):
        # A launch failure (no stage directory yet) reaches blue as a failed exit and so as a StepError.
        raise StepError("tofu output failed: [Errno 2] No such file or directory")

    assert await sut.read_state({}, launch_failure) == {"error": "tofu output failed: [Errno 2] No such file or directory"}
    assert await sut.read_state({}, nothing) == {"params": None}
    # params pass through, and the reader sees opts
    assert await sut.read_state({"profile": "prod"}, params) == {"params": {"ip": "1.2.3.4", "seen": "prod"}}
    # any other exception propagates
    with pytest.raises(RuntimeError, match="defect"):
        await sut.read_state({}, defect)
    with pytest.raises(TypeError, match="not a step error"):
        await sut.read_state({}, type_defect)


def test_provider_validator_pre_empts_the_thunk_on_a_mismatch():
    called = 0

    def thunk():
        nonlocal called
        called += 1
        return ["required credential is not set: COLORS_PAR_VULTR_API_KEY"]

    # mismatch pre-empts the thunk
    assert sut.provider_validator(spec, vultr(), {"provider": "digitalocean"}, thunk) == [
        "state holds a digitalocean machine; set provider-compute back to digitalocean and delete first"]
    assert called == 0
    # match calls it
    assert sut.provider_validator(spec, vultr(), {"provider": "vultr"}, thunk) == [
        "required credential is not set: COLORS_PAR_VULTR_API_KEY"]
    assert called == 1
    # no state calls it
    assert sut.provider_validator(spec, vultr(), None, thunk) == [
        "required credential is not set: COLORS_PAR_VULTR_API_KEY"]
    assert called == 2


def test_adopt_state_fails_closed_and_adopts_the_recorded_address(tmp_path, monkeypatch):
    opt_out = vultr(**{"vultr-ssh-keys": "key-uuid"})
    # error exits 1 with the delete wording and the reason
    refused = sut.adopt_state(opt_out, "delete", {"error": "HTTP 403 from backend"})
    assert refused["blue/exit"] == 1
    assert refused["blue/err"] == (
        "could not read the infrastructure state for the delete cleanup: HTTP 403 from backend\n"
        "fix the backend credentials and retry; a delete that cannot see its state has nothing to address")
    # rehearse wording
    rehearse = sut.adopt_state(opt_out, "rehearse", {"error": "HTTP 403 from backend"})
    assert rehearse["blue/exit"] == 1
    assert rehearse["blue/err"] == (
        "could not read the infrastructure state for rehearse: HTTP 403 from backend\n"
        "fix the backend credentials and retry; a rehearse that cannot see its state has nothing to address")
    # params merged; an ip already in opts does not override the recorded
    # address; ssh_key_id kept as written
    adopted = sut.adopt_state({**opt_out, "ip": "9.9.9.9"}, "delete",
                              {"params": {"ip": "1.2.3.4", "ssh_key_id": "77", "provider": "vultr"}})
    assert adopted["blue/exit"] == 0
    assert adopted["ip"] == "1.2.3.4"
    assert adopted["ssh_key_id"] == "77"
    assert "ssh-keygen" not in adopted
    # a readable state holding nothing leaves ip unset
    empty = sut.adopt_state(opt_out, "delete", {"params": None})
    assert empty["blue/exit"] == 0
    assert "ip" not in empty
    # keygen mode fills the machine key through once ssh, never touching the
    # real ~/.ssh
    monkeypatch.setenv("HOME", str(tmp_path))
    keygen = sut.adopt_state(vultr(), "delete", {"params": {"ip": "1.2.3.4"}})
    assert keygen["blue/exit"] == 0
    assert keygen["ssh-keygen"] is True
    assert keygen["vultr-ssh-keys"] == str(tmp_path / ".ssh" / "prod.pub")
    assert keygen["ssh-private-key-path"].startswith(str(tmp_path))
    assert Path(keygen["ssh-private-key-path"]).parent == tmp_path / ".ssh"


def test_fallback_params_carry_the_provider_and_lifecycle_event_covers_the_four_combinations():
    assert sut.fallback_params(vultr()) == {"provider": "vultr", "ip": "192.0.2.10", "user": "root", "sudoer": "root", "name": "prod"}
    assert sut.fallback_params(digitalocean(**{"digitalocean-name": "box"})) == {
        "provider": "digitalocean", "ip": "192.0.2.10", "user": "root", "sudoer": "root", "name": "box"}
    assert sut.lifecycle_event({"event": "create", "real": True}) is True
    assert sut.lifecycle_event({"event": "delete", "real": True}) is True
    assert sut.lifecycle_event({"event": "create", "real": False}) is False
    assert sut.lifecycle_event({"event": "build", "real": True}) is False
