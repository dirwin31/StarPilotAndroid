from pathlib import Path
import xml.etree.ElementTree as ET


ANDROID = "{http://schemas.android.com/apk/res/android}"
MANIFEST = Path(__file__).parents[1] / "app/src/main/AndroidManifest.xml"


def test_main_activity_handles_rotation_without_recreating_bluetooth_session():
  root = ET.parse(MANIFEST).getroot()
  activity = next(node for node in root.iter("activity") if node.get(ANDROID + "name") == ".MainActivity")
  changes = set(activity.get(ANDROID + "configChanges", "").split("|"))
  assert {"orientation", "screenSize", "screenLayout", "smallestScreenSize"} <= changes


def test_in_app_updater_has_scoped_install_permission_and_receiver():
  root = ET.parse(MANIFEST.parent.parent / "direct/AndroidManifest.xml").getroot()
  permissions = {node.get(ANDROID + "name") for node in root.findall("uses-permission")}
  assert "android.permission.REQUEST_INSTALL_PACKAGES" in permissions
  receiver = next(node for node in root.iter("receiver") if node.get(ANDROID + "name") == ".AppInstallReceiver")
  assert receiver.get(ANDROID + "exported") == "false"


def test_update_completion_survives_replacement_without_exporting_receiver():
  root = ET.parse(MANIFEST.parent.parent / "direct/AndroidManifest.xml").getroot()
  receiver = next(node for node in root.iter("receiver") if node.get(ANDROID + "name") == ".AppUpdateCompletion")
  assert receiver.get(ANDROID + "exported") == "false"
  assert {node.get(ANDROID + "name") for node in receiver.iter("action")} == {"android.intent.action.MY_PACKAGE_REPLACED"}


def test_shared_manifest_has_no_installer():
  root = ET.parse(MANIFEST).getroot()
  assert not any("AppInstall" in node.get(ANDROID + "name", "") for node in root.iter())
  assert "android.permission.REQUEST_INSTALL_PACKAGES" not in {node.get(ANDROID + "name") for node in root.findall("uses-permission")}


def test_built_flavors_keep_install_permission_out_of_play():
  import pytest
  build = MANIFEST.parents[2] / 'build/intermediates'
  manifests = list(build.glob('merged_manifest/*Release/*/AndroidManifest.xml'))
  if not manifests:
    pytest.skip('Run release manifest processing first')
  for path in manifests:
    permissions = {node.get(ANDROID + 'name') for node in ET.parse(path).getroot().findall('uses-permission')}
    assert 'com.google.android.gms.permission.AD_ID' not in permissions
    assert ('android.permission.REQUEST_INSTALL_PACKAGES' in permissions) == ('directRelease' in str(path))
    sdk = ET.parse(path).getroot().find('uses-sdk')
    assert sdk.get(ANDROID + 'targetSdkVersion') == '36'


def test_qr_scanner_is_private_and_camera_is_optional():
  root = ET.parse(MANIFEST).getroot()
  assert "android.permission.CAMERA" in {node.get(ANDROID + "name") for node in root.findall("uses-permission")}
  features = {node.get(ANDROID + "name"): node.get(ANDROID + "required") for node in root.findall("uses-feature")}
  assert features["android.hardware.camera"] == "false"
  assert features["android.hardware.camera.autofocus"] == "false"
  activity = next(node for node in root.iter("activity") if node.get(ANDROID + "name") == "com.journeyapps.barcodescanner.CaptureActivity")
  assert activity.get(ANDROID + "exported") == "false"
