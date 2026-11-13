"""Localhost command timeout must survive GPU-heavy shader startup."""
import unittest
from unittest.mock import patch
from visual_smoke import Rcon


class RconTimeoutTests(unittest.TestCase):
    def test_qualified_visual_fixture_allows_server_startup_stalls(self):
        with patch("visual_smoke.socket.create_connection") as connect, \
             patch.object(Rcon, "packet", return_value=""):
            conn = Rcon(25565)
            connect.assert_called_once_with(("127.0.0.1", 25565), timeout=30)
            self.assertIs(conn.sock, connect.return_value)

    def test_timeout_can_be_overridden_by_benchmark(self):
        with patch("visual_smoke.socket.create_connection") as connect, \
             patch.object(Rcon, "packet", return_value=""):
            Rcon(25565, timeout=90)
            connect.assert_called_once_with(("127.0.0.1", 25565), timeout=90)
