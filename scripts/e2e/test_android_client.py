"""Regression tests for the Android UI automation client."""

from __future__ import annotations

import unittest
from unittest import mock

from scripts.e2e.android_client import AndroidClient


class AndroidClientTest(unittest.TestCase):
    def test_get_text_of_id_does_not_depend_on_xml_attribute_order(self) -> None:
        hierarchy = """<?xml version="1.0" encoding="UTF-8"?>
        <hierarchy rotation="0">
          <node text="同步完成 &amp; 可用"
                bounds="[0,0][100,50]"
                resource-id="sync.status" />
        </hierarchy>
        """
        client = AndroidClient.__new__(AndroidClient)
        client.dump_hierarchy = mock.Mock(return_value=hierarchy)

        self.assertEqual(
            "同步完成 & 可用",
            client.get_text_of_id("sync.status", timeout=1),
        )


if __name__ == "__main__":
    unittest.main()
