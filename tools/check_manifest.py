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
receiver=next(n for n in root.find('application').findall('receiver') if n.get(ns+'name')=='.SmsReceiver')
assert receiver.get(ns+'exported')=='true'
assert receiver.get(ns+'permission')=='android.permission.BROADCAST_SMS'
assert any(n.get(ns+'name')=='android.provider.Telephony.SMS_RECEIVED' for n in receiver.findall('intent-filter/action'))
print('Manifest, resources and permission checks passed')

assert 'android.permission.READ_PHONE_STATE' in permissions
assert 'android.permission.READ_CALL_LOG' in permissions
assert 'android.permission.RECORD_AUDIO' not in permissions
assert 'android.permission.CALL_PHONE' not in permissions
call_receiver=next(n for n in root.find('application').findall('receiver') if n.get(ns+'name')=='.CallReceiver')
assert call_receiver.get(ns+'exported')=='true'
assert any(n.get(ns+'name')=='android.intent.action.PHONE_STATE' for n in call_receiver.findall('intent-filter/action'))
print('Incoming-call receiver and permission checks passed')
