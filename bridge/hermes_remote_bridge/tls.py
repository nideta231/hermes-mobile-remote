"""Self-signed TLS identity for local-network connections.

On Tailscale, WireGuard already encrypts and authenticates the PC. On a LAN nothing does, so the
bridge serves HTTPS there with a certificate the phone pins: its SHA-256 travels in the pairing
QR code. A device that merely holds the PC's old IP address cannot present that certificate, so
the app refuses it before sending the device token.

The certificate is generated with the ``cryptography`` package rather than the ``openssl`` CLI,
which Windows does not ship.
"""
from __future__ import annotations

import base64
import datetime
import hashlib
import os
import ssl
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def _private(path: Path, data: bytes) -> None:
    """Write ``data`` readable only by the owner. On Windows the mode bits are advisory; the file
    inherits the user-profile ACL, which already excludes other users."""
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as fh:
        fh.write(data)


def ensure_identity(directory: Path) -> tuple[Path, Path]:
    """Create (once) and return (cert.pem, key.pem): an ECDSA P-256 certificate valid 10 years."""
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    cert_path, key_path = directory / "tls-cert.pem", directory / "tls-key.pem"
    if cert_path.exists() and key_path.exists():
        return cert_path, key_path
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "hermes-remote-bridge")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    _private(key_path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                         serialization.NoEncryption()))
    cert_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    return cert_path, key_path


def cert_pin(cert: Path) -> str:
    """base64url(SHA-256(DER certificate)), unpadded. The value the app pins."""
    der = ssl.PEM_cert_to_DER_cert(cert.read_text())
    return base64.urlsafe_b64encode(hashlib.sha256(der).digest()).decode().rstrip("=")
