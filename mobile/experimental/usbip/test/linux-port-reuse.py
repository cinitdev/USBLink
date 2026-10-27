"""Optional Linux kernel probe for the USB listener's TIME_WAIT failure mode.

This checks socket semantics, not the Java server or Android device integration.
Run with python3 on Linux. All sockets bind loopback with OS-selected test ports.
"""
import errno
import socket
import sys


def listener(reuse, port=0):
    sock = socket.socket()
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, reuse)
        sock.bind(("127.0.0.1", port))
        sock.listen(1)
        return sock
    except BaseException:
        sock.close()
        raise


def active_close(reuse):
    with listener(reuse) as server:
        port = server.getsockname()[1]
        with socket.create_connection(("127.0.0.1", port), timeout=3) as client:
            accepted, _ = server.accept()
            with accepted:
                accepted.sendall(b"reply")
                accepted.shutdown(socket.SHUT_WR)
                with client.makefile("rb") as stream:
                    assert stream.read() == b"reply"
            # Server sends FIN first, as it does at the end of a USB/IP devlist.
    return port


if not sys.platform.startswith("linux"):
    raise SystemExit("Run this kernel probe on Linux; Windows has different reuse semantics.")

try:
    with listener(False, active_close(False)):
        raise AssertionError("Expected old policy to encounter TIME_WAIT")
except OSError as error:
    assert error.errno == errno.EADDRINUSE, error
    print("Old policy: reproduced EADDRINUSE after an actively closed connection")

for _ in range(5):
    with listener(True, active_close(True)) as restored:
        try:
            with listener(True, restored.getsockname()[1]):
                raise AssertionError("A live listener must not share its port")
        except OSError as error:
            assert error.errno == errno.EADDRINUSE, error
print("Fixed policy: immediate same-port bind passed 5 times; live conflicts still rejected")
