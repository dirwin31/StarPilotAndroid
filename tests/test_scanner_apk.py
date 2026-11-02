"""Check the shipped DEX, since Robolectric itself supplies AndroidX on the test classpath."""
from pathlib import Path
import struct
import zipfile


def defined_classes(dex):
  def uint(offset):
    return struct.unpack_from('<I', dex, offset)[0]

  strings_offset = uint(60)
  types_offset = uint(68)
  classes_count, classes_offset = uint(96), uint(100)
  for index in range(classes_count):
    type_index = uint(classes_offset + 32 * index)
    string_index = uint(types_offset + 4 * type_index)
    start = uint(strings_offset + 4 * string_index)
    # Skip the ULEB128 UTF-16 length prefix. Class descriptors are ASCII.
    while dex[start] & 0x80:
      start += 1
    start += 1
    yield dex[start:dex.index(b'\0', start)].decode('ascii')


def test_qr_scanner_dependencies_are_packaged():
  apk = Path(__file__).parents[1] / 'app/build/outputs/apk/play/debug/app-play-debug.apk'
  assert apk.exists(), 'Run assemblePlayDebug first'
  with zipfile.ZipFile(apk) as archive:
    classes = {name for entry in archive.namelist() if entry.startswith('classes') and entry.endswith('.dex')
               for name in defined_classes(archive.read(entry))}
  assert {
    'Lcom/journeyapps/barcodescanner/CaptureActivity;',
    'Lcom/journeyapps/barcodescanner/CaptureManager;',
    'Landroidx/core/content/ContextCompat;',
    'Landroidx/core/app/ActivityCompat;',
    'Lcom/google/zxing/MultiFormatReader;',
  } <= classes
