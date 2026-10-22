#!/usr/bin/env python3
"""Credential handling regressions for the local monitoring drill."""

import argparse
import base64
import importlib.util
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "local_monitoring", pathlib.Path(__file__).with_name("verify-outbox-local-monitoring.py"))
MONITORING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITORING)


class CredentialHandlingTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.options = argparse.Namespace(namespace="p722-local-test", context="local",
                                          output=directory.name, keep=False)
        self.drill = MONITORING.Drill(self.options)

    def test_credentials_are_independent_and_change_between_runs(self):
        second = MONITORING.Drill(self.options)
        credentials = list(self.drill.credentials.values()) + list(second.credentials.values())
        self.assertEqual(len(set(credentials)), 12)
        for credential in credentials:
            self.assertRegex(credential, r"^[0-9a-f]{48}$")
        self.assertEqual(list(self.drill.output.iterdir()), [])

    def test_secret_is_created_via_stdin_without_an_apply_annotation(self):
        with patch.object(MONITORING, "command", return_value="") as command:
            self.drill.apply("Secret", "credentials", stringData=self.drill.credentials)
        arguments, data = command.call_args.args
        self.assertEqual(arguments[-3:], ["create", "-f", "-"])
        self.assertNotIn("annotations", json.loads(data)["metadata"])
        self.assertEqual(json.loads(data)["stringData"], self.drill.credentials)
        self.assertTrue(command.call_args.kwargs["sensitive"])
        for credential in self.drill.credentials.values():
            self.assertNotIn(credential, " ".join(arguments))

    def test_database_password_is_supplied_on_stdin_with_errors_withheld(self):
        for role, key in (("app_migrator", "migrator"), ("app_worker", "worker")):
            with self.subTest(role=role), patch.object(MONITORING, "command", return_value="") as command:
                self.drill.set_role_password(role, key)
                arguments, data = command.call_args.args
                self.assertIn(self.drill.credentials[key], data)
                self.assertIn('ALTER ROLE :"role_name" PASSWORD :\'role_password\';', data)
                self.assertNotIn(self.drill.credentials[key], " ".join(arguments))
                self.assertTrue(command.call_args.kwargs["sensitive"])

    def test_sensitive_failure_does_not_leak_command_output(self):
        secret = self.drill.credentials["migrator"]
        result = subprocess.CompletedProcess(["kubectl"], 1, stdout=secret, stderr=secret)
        with patch.object(MONITORING.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as failure:
                self.drill.set_role_password("app_migrator", "migrator")
        self.drill.record("result", {"reason": str(failure.exception)})
        self.assertNotIn(secret, str(failure.exception))
        self.assertNotIn(secret, (self.drill.output / "result.json").read_text())
        self.assertIn("Sensitive command output withheld", str(failure.exception))

    def test_ordinary_failure_preserves_diagnostics(self):
        result = subprocess.CompletedProcess(["kubectl"], 1, stderr="deployment unavailable")
        with patch.object(MONITORING.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "deployment unavailable"):
                MONITORING.command(["kubectl", "get", "pods"])

    def test_http_authentication_uses_generated_credentials(self):
        for service, username, key in (("grafana", "admin", "grafana"), ("worker", "metrics", "metrics")):
            with self.subTest(service=service), patch.object(MONITORING.urllib.request, "urlopen") as urlopen:
                self.drill.ports[service] = 8080
                urlopen.return_value.__enter__.return_value.read.return_value = b"{}"
                self.drill.http(service, "/", credentials=(username, self.drill.credentials[key]))
                request = urlopen.call_args.args[0]
                scheme, encoded = request.get_header("Authorization").split(" ", 1)
                self.assertEqual(scheme, "Basic")
                self.assertEqual(base64.b64decode(encoded).decode(),
                                 f"{username}:{self.drill.credentials[key]}")
                self.assertNotIn(self.drill.credentials[key], request.full_url)


if __name__ == "__main__":
    unittest.main()
