#!/usr/bin/env python3
import os
import socket
import struct
import sys

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25575
TIMEOUT_SECONDS = 5.0


class RconError(RuntimeError):
    pass


def recv_exact(sock: socket.socket, size: int) -> bytes:
    chunks = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise RconError("RCON connection closed unexpectedly")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def encode_packet(request_id: int, packet_type: int, body: str) -> bytes:
    payload = body.encode("utf-8")
    packet_length = 4 + 4 + len(payload) + 2
    return struct.pack("<iii", packet_length, request_id, packet_type) + payload + b"\x00\x00"


def recv_packet(sock: socket.socket) -> tuple[int, int, str]:
    packet_length = struct.unpack("<i", recv_exact(sock, 4))[0]
    if packet_length < 10 or packet_length > 4096:
        raise RconError(f"Unexpected RCON packet length: {packet_length}")

    payload = recv_exact(sock, packet_length)
    request_id, packet_type = struct.unpack("<ii", payload[:8])

    if payload[-2:] != b"\x00\x00":
        raise RconError("Malformed RCON packet terminator")

    body = payload[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, body


def authenticate(sock: socket.socket, password: str) -> None:
    request_id = 1
    sock.sendall(encode_packet(request_id, 3, password))

    # Minecraft may send an empty RESPONSE_VALUE before AUTH_RESPONSE.
    for _ in range(3):
        response_id, response_type, _ = recv_packet(sock)
        if response_id == -1:
            raise RconError("RCON authentication failed")
        if response_type == 2 and response_id == request_id:
            return

    raise RconError("RCON authentication response was not received")


def execute(sock: socket.socket, command: str) -> str:
    request_id = 2
    sock.sendall(encode_packet(request_id, 2, command))
    response_id, response_type, body = recv_packet(sock)

    if response_id != request_id:
        raise RconError(f"Unexpected RCON response id: {response_id}")
    if response_type != 0:
        raise RconError(f"Unexpected RCON response type: {response_type}")

    return body


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: rcon.py <command>", file=sys.stderr)
        return 2

    password = os.environ.get("RCON_PASSWORD", "")
    if not password:
        print("RCON_PASSWORD is not set", file=sys.stderr)
        return 2

    host = os.environ.get("RCON_HOST", DEFAULT_HOST)
    try:
        port = int(os.environ.get("RCON_PORT", str(DEFAULT_PORT)))
    except ValueError:
        print("RCON_PORT must be an integer", file=sys.stderr)
        return 2

    command = " ".join(sys.argv[1:])

    try:
        with socket.create_connection((host, port), timeout=TIMEOUT_SECONDS) as sock:
            sock.settimeout(TIMEOUT_SECONDS)
            authenticate(sock, password)
            response = execute(sock, command)
    except (OSError, RconError) as exc:
        print(f"RCON error: {exc}", file=sys.stderr)
        return 1

    if response:
        print(response)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
