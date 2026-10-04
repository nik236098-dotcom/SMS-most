from pathlib import Path
import xml.etree.ElementTree as ET
base=Path(__file__).resolve().parents[1]
ns='{http://schemas.android.com/apk/res/android}'
root=ET.parse(base/'app/src/main/AndroidManifest.xml').getroot()
for node in root.find('application'):
    if node.tag not in ['activity','service','receiver']:continue
    name=node.get(ns+'name')
    file=base/'app/src/main/java/ru/smsbridge/app'/(name[1:]+'.java')
    assert file.is_file(),file
for xml in (base/'app/src/main/res').rglob('*.xml'):ET.parse(xml)
permissions={n.get(ns+'name') for n in root.findall('uses-permission')}
assert 'android.permission.RECEIVE_SMS' in permissions
assert 'android.permission.SEND_SMS' not in permissions
assert 'android.permission.READ_SMS' not in permissions
assert root.find('application').get(ns+'allowBackup')=='false'
print('Manifest, resources and permission checks passed')
