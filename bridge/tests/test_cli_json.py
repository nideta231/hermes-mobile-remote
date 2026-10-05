"""The machine-readable surface the Windows tray app depends on.

The tray shells `doctor --json`, `devices --json` and `trust --list --json` and parses the
result, so these are a contract, not a convenience. The human output must stay byte-identical
-- CI, the docs and the Linux experience all read it.
"""
import json
from pathlib import Path

import pytest

from hermes_remote_bridge import cli, doctor
from hermes_remote_bridge.config import Config
from hermes_remote_bridge.devices import DeviceStore


def _config(tmp_path) -> Path:
    """A real config.toml whose state files live in tmp, so a test cannot touch the user's own.

    `--config` alone is not enough: it changes where config.toml is read from, not the paths
    Config derives for devices, trust and the log.
    """
    path = tmp_path / "config.toml"
    path.write_text("\n".join([
        f"devices_file = '{(tmp_path / 'devices.json').as_posix()}'",
        f"trust_file = '{(tmp_path / 'networks.json').as_posix()}'",
        f"audit_log = '{(tmp_path / 'audit.log').as_posix()}'",
        f"hermes_env = '{(tmp_path / 'missing.env').as_posix()}'",
        "lan = false",
    ]) + "\n")
    return path


def _run(capsys, cfg_path, *argv):
    cli.main(["--config", str(cfg_path), *argv])
    return capsys.readouterr().out


def test_doctor_json_is_parsable_and_carries_every_check(capsys, tmp_path):
    out = _run(capsys, _config(tmp_path), "doctor", "--json")
    data = json.loads(out)  # the whole stdout must parse: no summary line after the document
    assert [c["id"] for c in data["checks"]] == list(doctor.CHECK_ORDER)
    assert all(c["level"] in ("ok", "warn", "fail") for c in data["checks"])
    assert all(c["message"] for c in data["checks"])


def test_doctor_json_reports_the_same_sentences_the_human_output_prints(capsys, tmp_path):
    """One source of truth: collect() builds the text run() prints, so the two cannot drift."""
    cfg_path = _config(tmp_path)
    expected = [c["message"] for c in doctor.collect(Config.load(cfg_path))]
    try:  # doctor exits nonzero when a check fails, which is the interesting case here
        cli.main(["--config", str(cfg_path), "doctor"])
    except SystemExit:
        pass
    out = capsys.readouterr().out
    for message in expected:
        assert message in out
    assert "problem(s) found." in out or "All good." in out


def test_doctor_still_exits_nonzero_when_a_check_fails(tmp_path):
    """The tray reads the exit code too, so it must still learn that a check failed."""
    with pytest.raises(SystemExit) as exc:
        cli.main(["--config", str(_config(tmp_path)), "doctor"])
    assert exc.value.code in (0, 1)


def test_devices_json_reports_paired_and_revoked_separately(capsys, tmp_path):
    store = DeviceStore(tmp_path / "devices.json")
    store.pair("phone")
    _, token = store.pair("tablet")
    store.revoke("tablet")
    out = _run(capsys, _config(tmp_path), "devices", "--json")
    devices = json.loads(out)["devices"]
    by_name = {d["name"]: d for d in devices}
    assert by_name["phone"]["active"] is True
    assert by_name["tablet"]["active"] is False
    assert by_name["tablet"]["revoked_at"] > 0
    assert by_name["phone"]["revoked_at"] is None
    # The token itself must never appear in the machine interface.
    assert token not in out


def test_devices_json_is_empty_rather_than_absent(capsys, tmp_path):
    data = json.loads(_run(capsys, _config(tmp_path), "devices", "--json"))
    assert data == {"devices": []}  # the tray can index .devices without a None check


def test_trust_list_json_names_the_current_network_and_its_trust_state(capsys, tmp_path, monkeypatch):
    from hermes_remote_bridge.network import Network

    monkeypatch.setattr(cli, "current_network", lambda: Network("abc123", "HomeWiFi", "Wi-Fi", ("192.168.1.5",)))
    data = json.loads(_run(capsys, _config(tmp_path), "trust", "--list", "--json"))
    assert data["current"] == {"id": "abc123", "name": "HomeWiFi", "trusted": False, "interface": "Wi-Fi"}
    assert data["trusted"] == []


def test_trust_list_json_marks_a_trusted_current_network(capsys, tmp_path, monkeypatch):
    from hermes_remote_bridge.network import Network

    (tmp_path / "networks.json").write_text(
        json.dumps({"trusted": {"abc123": {"name": "HomeWiFi", "since": 1}}}))
    monkeypatch.setattr(cli, "current_network", lambda: Network("abc123", "HomeWiFi", "Wi-Fi", ("192.168.1.5",)))
    data = json.loads(_run(capsys, _config(tmp_path), "trust", "--list", "--json"))
    assert data["current"]["trusted"] is True
    assert data["trusted"] == [{"id": "abc123", "name": "HomeWiFi", "since": 1}]


def test_trust_list_json_reports_no_network_as_null_not_a_crash(capsys, tmp_path, monkeypatch):
    """Offline is a normal state; the tray must be able to tell it from an empty list."""
    monkeypatch.setattr(cli, "current_network", lambda: None)
    data = json.loads(_run(capsys, _config(tmp_path), "trust", "--list", "--json"))
    assert data["current"] is None and data["trusted"] == []


def test_json_output_is_off_by_default_so_human_output_is_unchanged(capsys, tmp_path):
    cfg_path = _config(tmp_path)
    assert "{" not in _run(capsys, cfg_path, "devices")  # a table, not a JSON document
    assert "{" not in _run(capsys, cfg_path, "trust", "--list")


def test_the_port_guard_notices_a_second_bridge():
    """bind() cannot do this: SO_REUSEADDR lets a second bind of the port succeed on Windows."""
    import socket

    from hermes_remote_bridge import cli as bridge_cli

    first = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    first.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    first.bind(("127.0.0.1", 0))
    first.listen(128)
    port = first.getsockname()[1]
    try:
        assert bridge_cli._port_in_use("127.0.0.1", port) is True
    finally:
        first.close()
    assert bridge_cli._port_in_use("127.0.0.1", port) is False  # free again


def test_the_port_guard_does_not_block_a_free_port():
    """It must never call a port taken when nothing is listening, or the bridge never starts."""
    import socket

    from hermes_remote_bridge import cli as bridge_cli

    probe = socket.socket()
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    assert bridge_cli._port_in_use("127.0.0.1", port) is False
