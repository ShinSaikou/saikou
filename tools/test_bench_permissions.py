#!/usr/bin/env python3
"""Unit tests for the permission reading in tools/bench/common.sh.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

`grant_runtime_permissions` runs whenever common.sh is sourced and stops the bench while any
permission is still to grant. It used to read "still to grant" as any line of
`dumpsys package` saying `granted=false`, install section included. An install-time permission gated
by an app op reads exactly that once the user has set the op: upstream v0.20.4's
REQUEST_INSTALL_PACKAGES does after its first run turns on "Install unknown apps", which the library
update bench (ruling R66) needs to install its fixture extension. `pm grant` can never change an
install-time permission, so every bench stopped on that device with the op set to allow.

`permission_lines` now reads the section a line sits in. The function is taken out of common.sh by
text, as tools/test_bench_launch.py does with `assert_launched`, and run against a dumpsys excerpt
captured from that device, with `$ADB` pointing at a shell function that prints it.
"""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

from test_bench_launch import COMMON_SH, extract_function

PERMISSION_LINES = extract_function(COMMON_SH.read_text(), "permission_lines")

# From `dumpsys package <the upstream app's package>` on saikou_pixel9_4g after its first run, trimmed.
DUMPSYS = """\
    requested permissions:
      android.permission.INTERNET
      android.permission.REQUEST_INSTALL_PACKAGES
      android.permission.POST_NOTIFICATIONS
    install permissions:
      android.permission.INTERNET: granted=true
      android.permission.REQUEST_INSTALL_PACKAGES: granted=false, flags=[ USER_SET]
      android.permission.WAKE_LOCK: granted=true
    User 0: ceDataInode=1 installed=true hidden=false suspended=false distractionFlags=0 stopped=false
      runtime permissions:
        android.permission.POST_NOTIFICATIONS: granted=%s, flags=[ USER_SET]
        android.permission.READ_MEDIA_IMAGES: granted=true, flags=[ USER_SET]
"""


def permission_lines(*args: str, notifications: str = "true") -> list[str]:
    script = (
        f"fake_adb() {{ printf '%s' \"$DUMPSYS\"; }}\n"
        "ADB=fake_adb\n"
        f"{PERMISSION_LINES}\n"
        'permission_lines "$@"\n'
    )
    result = subprocess.run(["bash", "-c", script, "bash", *args],
                            env={"PATH": "/usr/bin:/bin", "SAIKOU_PACKAGE": "org.example.upstream",
                                 "DUMPSYS": DUMPSYS % notifications},
                            capture_output=True, text=True, check=True)
    return result.stdout.split()


class PermissionLinesTest(unittest.TestCase):
    def test_every_permission_with_a_state_is_listed(self):
        self.assertEqual(permission_lines("granted="), [
            "android.permission.INTERNET",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.WAKE_LOCK",
        ])

    def test_an_install_permission_behind_an_app_op_is_not_still_to_grant(self):
        self.assertEqual(permission_lines("granted=false", "runtime"), [])
        # Read without the section, it is the line that used to stop every bench.
        self.assertEqual(permission_lines("granted=false"), ["android.permission.REQUEST_INSTALL_PACKAGES"])

    def test_an_ungranted_runtime_permission_is_still_to_grant(self):
        self.assertEqual(permission_lines("granted=false", "runtime", notifications="false"),
                         ["android.permission.POST_NOTIFICATIONS"])


if __name__ == "__main__":
    unittest.main()
