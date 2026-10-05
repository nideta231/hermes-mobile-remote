"""Command-line entry point: run the bridge and manage paired devices and trusted networks."""
from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import logging
import os
import socket
import sys
import threading
import time
import urllib.parse
from datetime import datetime
from pathlib import Path

from .config import Config
from .devices import DeviceStore
from .host import host
from .network import TrustStore, current_network, serving_lan_ips
from .tailnet import sync_tailscale_ips
from .tls import cert_pin, ensure_identity


def _plain_hosts(cfg: Config, retries: int) -> tuple[list[str], str | None]:
    """Loopback + Tailscale: plain HTTP (WireGuard already encrypts the tailnet)."""
    hosts: list[str] = []
    owner = None
    for entry in cfg.listen:
        if entry != "tailscale":
            hosts.append(entry)
            continue
        # Tailscale is an extension, not a requirement: keep serving loopback/LAN without it.
        for attempt in range(retries):  # tailscaled may still be starting at boot
            try:
                ips, owner = sync_tailscale_ips()
                hosts.extend(ips)
                break
            except Exception as exc:
                if attempt == retries - 1:
                    logging.getLogger("hermes_remote_bridge").warning(
                        "Tailscale unavailable (%s); serving without tailnet addresses", exc)
                else:
                    time.sleep(2)
    if any(ipaddress.ip_address(h).is_unspecified for h in hosts):
        raise SystemExit("Refusing to bind a wildcard address (0.0.0.0 / ::); list explicit addresses")
    return hosts, owner


def _port_in_use(host: str, port: int) -> bool:
    """True when something already accepts connections on ``host:port``.

    ``bind()`` cannot be relied on to notice: ``_bind`` sets SO_REUSEADDR, and on Windows that
    flag lets a *second* bind of the same address/port succeed (on Linux it only covers
    TIME_WAIT, so this matters most there). Two bridges would then silently share the port
    and connections would land on whichever socket the stack picked. A TCP connect is the
    check that behaves the same on every platform.
    """
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    probe = socket.socket(family, socket.SOCK_STREAM)
    try:
        probe.settimeout(2)
        probe.connect((host, port))
    except OSError:
        return False
    finally:
        probe.close()
    return True


def _bind(host: str, port: int) -> socket.socket:
    """Bind one listening socket, refusing if the port is already served.

    SO_REUSEADDR is set for TIME_WAIT (Linux) and is harmless there, but on Windows it also lets a
    *second* bind of the same address/port succeed, so bind() alone is not a guard. The check
    below is deliberately not atomic with the bind: between the connect and the bind another
    process can win the port, and then bind() raises and we surface that. What the check buys is
    a clear message for the common case (a bridge is already running) instead of a traceback.
    """
    if _port_in_use(host, port):
        raise SystemExit(
            f"Port {port} is already in use on {host}; another bridge is probably running. "
            f"Stop it first (the tray: Quit Hermes Remote), or set a different port in config.toml.")
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    if family == socket.AF_INET6:
        sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
    sock.bind((host, port))
    sock.listen(128)
    sock.setblocking(False)
    return sock


def _desired(cfg: Config, trust: TrustStore) -> tuple[frozenset[str], frozenset[str]]:
    """(plain hosts, LAN TLS hosts) the bridge should be listening on right now."""
    plain = {h for h in cfg.listen if h != "tailscale"}
    if "tailscale" in cfg.listen:
        try:
            plain.update(sync_tailscale_ips()[0])
        except Exception:
            pass
    return frozenset(plain), frozenset(serving_lan_ips(cfg.lan, trust))


def cmd_serve(cfg: Config, _: argparse.Namespace) -> None:
    import uvicorn

    from .app import create_app

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)  # the network watcher polls every 5 s
    log = logging.getLogger("hermes_remote_bridge")
    trust = TrustStore(cfg.trust_file)
    plain, owner = _plain_hosts(cfg, retries=3 if cfg.lan else 30)
    lan = serving_lan_ips(cfg.lan, trust)
    if not cfg.allowed_logins and not owner:
        if not cfg.lan:
            raise SystemExit("Could not determine the Tailscale owner; set allowed_logins in config.toml")
        log.warning("Tailscale unavailable; tailnet peers are refused until it is back")
    net = current_network()
    if cfg.lan:
        log.info("network: %s (%s)", net.name if net else "none",
                 "trusted, serving the local network" if lan else
                 "not trusted, local network off" if net else "offline")

    app = create_app(cfg, owner_login=owner, trust=trust)
    servers = [uvicorn.Server(uvicorn.Config(app, access_log=False, log_level="info",
                                             timeout_graceful_shutdown=5, proxy_headers=False))]
    # _bind checks each address for itself and reports the conflict by name, so there is no
    # separate sweep here to keep in sync with it.
    sockets = [[_bind(h, cfg.port) for h in plain]]
    pin = None
    if lan:
        cert, key = ensure_identity(cfg.tls_dir)
        pin = cert_pin(cert)
        # Same app, second server: TLS only on the LAN. lifespan off so housekeeping and
        # shutdown run once (owned by the plain server).
        servers.append(uvicorn.Server(uvicorn.Config(
            app, access_log=False, log_level="info", lifespan="off", timeout_graceful_shutdown=5,
            proxy_headers=False, ssl_certfile=str(cert), ssl_keyfile=str(key))))
        sockets.append([_bind(h, cfg.port) for h in lan])
    log.info("listening on %s%s", ", ".join(f"http://{h}:{cfg.port}" for h in plain),
             "".join(f", https://{h}:{cfg.port}" for h in lan))
    mdns = _advertise(cfg, pin, sorted(lan)) if lan and cfg.mdns else None
    if lan and cfg.mdns and mdns is None:
        log.warning("mDNS unavailable on this PC; the app can't follow an IP change by itself")
    if cfg.lan and net and not lan:
        _ask_to_trust(trust, net)
    try:
        changed = asyncio.run(_serve_until_network_changes(servers, sockets, cfg, trust,
                                                          (frozenset(plain), frozenset(lan))))
    finally:
        if mdns:
            mdns.terminate()
    if changed:
        # Non-zero so the service manager (systemd Restart=always, a Scheduled Task's restart
        # policy) brings us back up on the new addresses.
        raise SystemExit(75)


def _listener_closed(sockets) -> bool:
    """True when any bound listening socket has been closed (its descriptor is gone)."""
    return any(sock.fileno() == -1 for group in sockets for sock in group)


async def _serve_until_network_changes(servers, sockets, cfg: Config, trust: TrustStore,
                                       bound: tuple[frozenset[str], frozenset[str]]) -> bool:
    """Serve until Wi-Fi/DHCP/Tailscale/trust changes what we should listen on.

    Sockets are bound to explicit addresses (never 0.0.0.0), so a new network would otherwise
    leave the phone unable to connect until someone restarts the service.
    """
    changed = False

    async def watch():
        nonlocal changed
        tick = 0
        while not any(s.should_exit for s in servers):
            await asyncio.sleep(1)
            tick += 1
            if _listener_closed(sockets):
                # asyncio on Windows closes a listening socket when one accept() fails (a client
                # resetting mid-connect is enough). The process would stay up, deaf. Restart.
                logging.getLogger("hermes_remote_bridge").error(
                    "a listening socket was closed underneath us; restarting")
                changed = True
                for s in servers:
                    s.should_exit = True
                return
            if tick % 5:
                continue
            now = await asyncio.to_thread(_desired, cfg, trust)
            if now != bound and (now[0] or now[1]):
                logging.getLogger("hermes_remote_bridge").info(
                    "listen set changed (%s -> %s); restarting",
                    sorted(bound[0] | bound[1]), sorted(now[0] | now[1]))
                changed = True
                for s in servers:
                    s.should_exit = True

    task = asyncio.create_task(watch())
    try:
        await asyncio.gather(*(srv.serve(sockets=socks) for srv, socks in zip(servers, sockets)))
    finally:
        task.cancel()
    return changed


def _advertise(cfg: Config, pin: str | None, lan_ips: list[str]):
    """Announce the bridge over mDNS so the app finds it when the PC's IP changes.

    The TXT record carries only a short prefix of the certificate pin, so the app can tell its
    own bridge from another one; the full pin is still checked on the TLS connection. ``ip=``
    lists the addresses we actually serve, so a resolver never hands the phone a Docker bridge.
    """
    return host().advertise_mdns(cfg.port, pin, lan_ips)


def _ask_to_trust(trust: TrustStore, net) -> None:
    """Desktop prompt: offer to trust the network the PC just joined (once per network)."""
    if net.id in trust.declined():
        return

    def ask():
        # Re-read the network rather than trusting the value captured at startup: the tray is
        # long-lived, and by the time this runs the PC may be somewhere else. current_network is
        # cached for a second, which is fresh enough for a prompt.
        current = current_network()
        if current is None or current.id != net.id:
            return  # the network changed again; the next watcher pass will ask about the new one
        answer = host().prompt_trust(current.name)
        if answer == "trust":
            trust.trust(current)  # the watcher sees it and restarts with the LAN listener
        elif answer == "no":
            trust.decline(current)

    threading.Thread(target=ask, daemon=True).start()


def _bridge_urls(cfg: Config) -> list[str]:
    """Addresses for the pairing code: trusted LAN (HTTPS) first, Tailscale as the fallback."""
    lan = serving_lan_ips(cfg.lan, TrustStore(cfg.trust_file))
    try:
        tailnet = [ip for ip in sync_tailscale_ips()[0] if "." in ip]
    except Exception:
        tailnet = []  # Tailscale is optional
    urls = [f"https://{ip}:{cfg.port}" for ip in lan] + [f"http://{ip}:{cfg.port}" for ip in tailnet]
    if not urls:
        raise SystemExit("No trusted local network and no Tailscale: nothing to put in the pairing code. "
                         "Run `hermes-remote-bridge trust` on a network you control, or start Tailscale.")
    return urls


def _pairing_warnings(cfg: Config, urls: list[str]) -> list[str]:
    """Reasons the address in the pairing code may be unreachable from the phone.

    Printed before the QR, because "the app says it cannot reach the PC" is the usual first
    outcome and it is nearly always one of these two.
    """
    from . import firewall

    out: list[str] = []
    lan = [u for u in urls if u.startswith("https://")]  # LAN entries are HTTPS; tailnet is HTTP
    if not lan and cfg.lan:
        out.append("No trusted local network, so the code points at Tailscale only. The phone can "
                   "only use it with Tailscale installed and logged into the same tailnet.\n"
                   f"    Fix: hermes-remote-bridge trust   (on your home or office Wi-Fi)")
    if lan:
        state = firewall.state(cfg.port)
        if state.active and state.port_open is False:
            out.append(f"{state.kind} firewall is blocking TCP {cfg.port}, so nothing on this network "
                       "can reach the bridge. This is on by default on Windows.\n"
                       f"    Fix: hermes-remote-bridge firewall   (asks first)")
        elif state.active and state.port_open is None:
            out.append(f"{state.kind} firewall is active and I cannot read its rules. If the app cannot "
                       f"reach the PC, open TCP {cfg.port} from your local network:\n"
                       f"    Fix: hermes-remote-bridge firewall")
        out.append("The phone must be on the SAME Wi-Fi as this PC, not on mobile data or a different "
                   "network. Guest Wi-Fi often blocks device-to-device traffic.")
    return out


def _show_qr(uri: str, cfg: Config) -> bool:
    """Show the pairing QR. Returns True when it was opened as an image file.

    A terminal QR is drawn with block characters. That works in a Linux terminal, but a Windows
    console may not have the glyphs (or the code page) and the result is unscannable or an
    exception, so there it opens as a picture; it is also the fallback if printing fails.
    """
    import segno

    qr = segno.make(uri, error="m")
    if not host().qr_as_image():
        try:
            qr.terminal(compact=True)
            return False
        except UnicodeEncodeError:
            pass
    path = cfg.audit_log.parent / "pairing-qr.png"
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        qr.save(fh, kind="png", scale=8, border=3, dark="#000", light="#fff")
    if host().open_file(path):
        return True
    print(f"Open this picture and scan it: {path}  (delete it afterwards)")
    return False


def cmd_pair(cfg: Config, args: argparse.Namespace) -> None:
    store = DeviceStore(cfg.devices_file)
    device, token = store.pair(args.name)
    urls = [args.url] if args.url else _bridge_urls(cfg)
    # v2: LAN entries are HTTPS and `pin` is the certificate the app must see before it sends
    # the token there. Tailscale entries stay HTTP inside WireGuard.
    payload = {"v": 2, "url": urls[0], "device": device.name, "token": token,
               "pin": cert_pin(ensure_identity(cfg.tls_dir)[0])}
    if len(urls) > 1:
        payload["alt"] = ",".join(urls[1:])
    if args.token_file:  # non-interactive use; never print the token
        fd = os.open(args.token_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as fh:
            json.dump(payload, fh)
        print(f"Paired {device.name} ({device.id}); credentials written to {args.token_file}")
        return
    import segno

    uri = "hermesremote://pair?" + urllib.parse.urlencode(payload)
    if args.qr_png:
        fd = os.open(args.qr_png, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as fh:
            segno.make(uri, error="m").save(fh, kind="png", scale=8, border=3, dark="#000", light="#fff")
        print(f"Paired {device.name} ({device.id}); QR written to {args.qr_png} (delete it after scanning)")
        return
    print(f"Paired device {device.name!r} (id {device.id}).")
    warnings = [] if args.url else _pairing_warnings(cfg, urls)
    if warnings:
        print("\nBEFORE you scan, check this:")
        for w in warnings:
            print(f"  * {w}")
        print()
    print("Scan this QR code in the Hermes Remote app. It is shown ONCE; the token is not stored.\n")
    shown_as_image = _show_qr(uri, cfg)
    print(f"\nManual entry -> URL: {urls[0]}\n                 Token: {token}\n")
    if shown_as_image:
        print("The QR code was opened as a picture. Close it and delete it once the app has paired.")
    print(f"Revoke any time: hermes-remote-bridge revoke {device.name!r}")
    _confirm_reachable(urls[0], cfg)


def _confirm_reachable(url: str, cfg: Config) -> None:
    """Try the address in the pairing code, so a blocked port shows up here and not only on the phone.

    On the LAN this is HTTPS with the bridge's own self-signed certificate, so we load that
    certificate as the trust anchor instead of the system store: a plain verification failure would
    be reported as "unreachable" when the port is in fact open.
    """
    import ssl
    import urllib.error
    import urllib.request

    ctx = ssl.create_default_context()
    if url.startswith("https://"):
        # The certificate names no IP and is not signed by anyone, so the app pins its fingerprint
        # instead of trusting a CA. Check it the same way here, or every healthy bridge looks dead.
        # check_hostname is off because there is no hostname to match.
        try:
            cert_path, _key_path = ensure_identity(cfg.tls_dir)
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_REQUIRED
            ctx.load_verify_locations(cadata=cert_path.read_text())
        except (OSError, ssl.SSLError):
            ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(url + "/v1/me", timeout=5, context=ctx) as r:
            print(f"Reachable: {url} (HTTP {r.status})")
            return
    except urllib.error.HTTPError as exc:
        # Any HTTP answer means the port is open and something is listening. 401 is correct.
        print(f"Reachable: {url} (HTTP {exc.code})")
        return
    except Exception as exc:  # noqa: BLE001 - the point is to report whatever went wrong
        print(f"NOT reachable from this PC: {url} ({exc}).")
        print("  The phone will not reach it either. Run: hermes-remote-bridge doctor")


def cmd_revoke(cfg: Config, args: argparse.Namespace) -> None:
    device = DeviceStore(cfg.devices_file).revoke(args.device)
    print(f"Revoked {device.name} ({device.id}). Takes effect on its next request.")


def cmd_trust(cfg: Config, args: argparse.Namespace) -> None:
    trust = TrustStore(cfg.trust_file)
    if args.list:
        current = current_network()
        rows = trust.list()
        if getattr(args, "json", False):
            print(json.dumps({
                "current": ({"id": current.id, "name": current.name, "trusted": current.id in rows,
                             "interface": current.interface} if current else None),
                "trusted": [{"id": net_id, "name": info.get("name", "?"), "since": info.get("since")}
                            for net_id, info in rows.items()],
            }, indent=2))
            return
        if not rows:
            print("No trusted networks.")
        for net_id, info in rows.items():
            mark = "  <- current" if current and current.id == net_id else ""
            print(f"{net_id}  {info.get('name', '?')}{mark}")
        if current and current.id not in rows:
            print(f"Current network {current.name!r} ({current.id}) is not trusted.")
        return
    if args.remove:
        print("Removed." if trust.untrust(args.remove) else f"No trusted network {args.remove!r}.")
        return
    net = current_network()
    if net is None:
        raise SystemExit("Not on a private network (or the router has not been seen yet).")
    trust.trust(net)
    print(f"Trusted {net.name!r} ({net.id}). The bridge picks it up within a few seconds.")


def cmd_devices(cfg: Config, args: argparse.Namespace) -> None:
    fmt = lambda ts: datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M") if ts else "-"
    rows = DeviceStore(cfg.devices_file).list()
    if getattr(args, "json", False):
        print(json.dumps({"devices": [
            {"id": d.id, "name": d.name, "active": d.active, "created_at": d.created_at,
             "last_seen_at": d.last_seen_at, "revoked_at": d.revoked_at} for d in rows]}, indent=2))
        return
    if not rows:
        print("No paired devices.")
    for d in rows:
        state = "active" if d.active else f"revoked {fmt(d.revoked_at)}"
        print(f"{d.id}  {d.name:<24} paired {fmt(d.created_at)}  last seen {fmt(d.last_seen_at)}  {state}")


def cmd_doctor(cfg: Config, args: argparse.Namespace) -> None:
    from . import doctor
    if getattr(args, "json", False):
        print(json.dumps({"checks": doctor.collect(cfg)}, indent=2))
        return  # the JSON document is the whole answer; a summary line would corrupt it
    sys.exit(doctor.run(cfg))


def cmd_firewall(cfg: Config, args: argparse.Namespace) -> None:
    from . import firewall
    s = firewall.state(cfg.port)
    if s.kind == "none" or not s.active:
        print(f"No active firewall; port {cfg.port} is reachable.")
        return
    if s.port_open:
        print(f"{s.kind}: port {cfg.port} is already allowed from private networks.")
        return
    if s.kind == "unknown" or not firewall.open_commands(s.kind, cfg.port):
        print(f"Can't manage this firewall automatically. Allow TCP {cfg.port} from your local network yourself.")
        return
    if args.if_needed and s.port_open is None and not sys.stdin.isatty():
        return
    print(f"{s.kind} is active. To let your phone reach the bridge over Wi-Fi, this will be added")
    print("(private networks only; the bridge only listens on Wi-Fi you trusted):")
    for c in firewall.open_commands(s.kind, cfg.port):
        print("  " + " ".join(c))
    if not args.yes:
        try:
            if input("Apply now? You'll be asked for your password"
                         f"{' (a UAC prompt)' if s.kind == 'windows' else ''}. [Y/n] ").strip().lower() not in ("", "y", "yes"):
                return
        except EOFError:
            return
    print("Done." if firewall.open_port(s.kind, cfg.port) else "Not applied (cancelled or failed).")


def _no_command_help(parser: argparse.ArgumentParser) -> None:
    """Running the program with nothing after it: say what to type, and don't vanish.

    On Windows people double-click the .exe; without this the window shows an argparse error and
    closes before it can be read.
    """
    print("Hermes Mobile Remote bridge\n")
    print("This program needs a command after its name. The ones you want most:\n")
    print("  hermes-remote-bridge pair phone     show a QR code to connect your phone")
    print("  hermes-remote-bridge doctor         find out why the phone cannot connect")
    print("  hermes-remote-bridge devices        list paired phones")
    print("  hermes-remote-bridge trust          trust the Wi-Fi network you are on now")
    if host().name == "windows":
        print("\nEasiest: double-click  pair.cmd  in the project folder.")
    print("\nAll commands: hermes-remote-bridge --help")
    if host().launched_by_double_click():
        input("\nPress Enter to close this window...")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="hermes-remote-bridge")
    parser.add_argument("--config", type=Path, help="config.toml (default ~/.config/hermes-remote/config.toml)")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("serve", help="Run the bridge")
    p = sub.add_parser("pair", help="Pair a new device and show its QR code")
    p.add_argument("name", help="Device name, e.g. 'phone' or 'tablet'")
    p.add_argument("--url", help="Override the bridge URL put in the QR code")
    p.add_argument("--token-file", type=Path, help=argparse.SUPPRESS)
    p.add_argument("--qr-png", type=Path, help="Write the pairing QR to a PNG (mode 600) instead of the terminal")
    r = sub.add_parser("revoke", help="Revoke a paired device")
    r.add_argument("device", help="Device name or id")
    d = sub.add_parser("devices", help="List paired devices")
    d.add_argument("--json", action="store_true", help="Machine-readable output (for the tray app)")
    t = sub.add_parser("trust", help="Trust the network this PC is on (serve the LAN there)")
    t.add_argument("--list", action="store_true", help="List trusted networks")
    t.add_argument("--remove", metavar="ID", help="Stop trusting a network")
    t.add_argument("--json", action="store_true", help="Machine-readable output, with --list")
    doc = sub.add_parser("doctor", help="Check why the phone can't connect")
    doc.add_argument("--json", action="store_true", help="Machine-readable checks (for the tray app)")
    f = sub.add_parser("firewall", help="Allow the bridge port from the local network (asks first)")
    f.add_argument("--yes", action="store_true", help="Don't ask for confirmation")
    f.add_argument("--if-needed", action="store_true", help=argparse.SUPPRESS)
    if not (argv if argv is not None else sys.argv[1:]):
        _no_command_help(parser)
        return
    args = parser.parse_args(argv)
    cfg = Config.load(args.config)
    handler = {"serve": cmd_serve, "pair": cmd_pair, "revoke": cmd_revoke, "devices": cmd_devices,
               "trust": cmd_trust, "doctor": cmd_doctor, "firewall": cmd_firewall}[args.command]
    try:
        handler(cfg, args)
    except ValueError as exc:
        sys.exit(f"error: {exc}")


if __name__ == "__main__":
    main()
