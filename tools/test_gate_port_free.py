#!/usr/bin/env python3
"""Unit tests for gate.sh's port check before step 5f starts its fixture server (R117 carry-over 4).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The frozen fixture extension only ever calls port 18794, so step 5f's download fixture server has
to take that port, and a unit's fixture device session on the same machine serves it too. Before
the check, a second server started beside the first. Nothing here runs a gate step: `port_free` is
read out of gate.sh as written and run in bash against a listener this test opens on port 0, never
on 18794.

The cases that matter, and why each one is here:
  - a port something listens on is not free, and the same port once closed is;
  - steps 5f and 5i (phase 5 unit TG4) call the check, and fail naming the port, before each starts
    its server, and step 5i checks the port is free again once its server stopped.
"""

from __future__ import annotations

import re
import socket
import subprocess
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
GATE = TOOLS / "ci" / "gate.sh"


def port_free_function() -> str:
    """The function's text as gate.sh defines it, from its first line to its closing brace."""
    match = re.search(r"^port_free\(\) \{.*?^\}$", GATE.read_text(), re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError("gate.sh defines no port_free function")
    return match.group(0)


def port_free(port: int) -> int:
    script = port_free_function() + f"\nport_free {port}\n"
    return subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=30).returncode


class PortFreeTest(unittest.TestCase):

    def test_a_port_with_a_listener_is_not_free_and_is_once_closed(self):
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        port = listener.getsockname()[1]
        try:
            self.assertNotEqual(port_free(port), 0, f"port {port} has a listener")
        finally:
            listener.close()
        self.assertEqual(port_free(port), 0, f"port {port} was closed")

    def test_steps_5f_and_5i_check_the_port_before_each_starts_its_server(self):
        text = GATE.read_text()
        for name in ("5f", "5i"):
            with self.subTest(step=name):
                step = text[text.index(f'step "{name}.'):]
                step = step[:step.index("\nfi\n")]
                self.assertIn("port_free 18794 ||", step, f"step {name} starts its server without checking the port")
                check = step.index("port_free 18794 ||")
                start = step.index("start_fixture_server")
                self.assertLess(check, start, "the check comes after the server starts")
                failure = step[check:start]
                self.assertIn("fail ", failure)
                self.assertIn("port 18794 is in use", failure)

    def test_step_5i_checks_the_port_is_free_again_after_its_server_stopped(self):
        text = GATE.read_text()
        step = text[text.index('step "5i.'):]
        step = step[:step.index("\nfi\n")]
        stop = step.index("stop_fixture_server")
        self.assertLess(step.index("start_fixture_server"), stop)
        self.assertRegex(step[stop:], r'run "[^"]*" port_free 18794\n')


if __name__ == "__main__":
    unittest.main()
