#!/usr/bin/env python3
"""Build an experimental protected LPA companion from a supplied, reviewed bridge checkout.
No upstream binaries or shared key are included in this project's zip.
The operator must verify card authorization for this key on their own 9eSIM V0.
"""
import argparse, pathlib, subprocess, os, re, shutil, xml.etree.ElementTree as ET, zipfile
def run(args): subprocess.run([str(x) for x in args],check=True)
p=argparse.ArgumentParser();p.add_argument('--bridge',required=True);p.add_argument('--relay-apk',required=True)
p.add_argument('--sdk',default=os.environ.get('ANDROID_HOME',''));p.add_argument('--community',default='vendor/community');a=p.parse_args()
base=pathlib.Path(__file__).resolve().parents[1];vendor=pathlib.Path(a.bridge).resolve();sdk=pathlib.Path(a.sdk)
bt=sdk/'build-tools'/'35.0.0';android=sdk/'platforms'/'android-35'/'android.jar'
if not android.is_file():raise SystemExit('Android SDK 35 is required')
source=vendor/'src'/'im'/'angry'/'openeuicc'/'bridge'/'LpaProvider.java'
original=vendor/'eazyeuicc.apk';deps=list((vendor/'deps').glob('*.jar'));baksmali=vendor/'tools'/'baksmali.jar'
if not source.is_file() or not original.is_file() or not deps or not baksmali.is_file():raise SystemExit('Incomplete upstream bridge checkout')
verify=subprocess.check_output([str(bt/'apksigner'),'verify','--print-certs',a.relay_apk],text=True)
match=re.search(r'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]{64})',verify)
if not match:raise SystemExit('Cannot read relay signing certificate')
sha=match[1].lower();work=base/'build'/'companion'
if work.exists():shutil.rmtree(work)
work.mkdir(parents=True);tree=work/'decoded';classes=work/'classes';dex=work/'dex';smali=work/'smali'
for d in [classes,dex,smali]:d.mkdir()
run(['apktool','d','-f',original,'-o',tree])
gate=work/'GatewayProvider.java';gate.write_text((base/'tools'/'GatewayProvider.java').read_text().replace('@@RELAY_SHA256@@',sha))
cp=os.pathsep.join(map(str,[android,*deps]))
sources=list((vendor/'src').rglob('*.java'))
run(['javac','-source','17','-target','17','-encoding','UTF-8','-classpath',cp,'-d',classes,*sources,gate])
opts=[]
for jar in deps:opts+=['--classpath',jar]
run([bt/'d8','--min-api','28','--lib',android,*opts,'--output',dex,*classes.rglob('*.class')])
for d in dex.glob('*.dex'):run(['java','-jar',baksmali,'d',d,'-o',smali])
n=2
while (tree/f'smali_classes{n}').exists():n+=1
shutil.copytree(smali,tree/f'smali_classes{n}')
ns='http://schemas.android.com/apk/res/android';ET.register_namespace('android',ns)
manifest=tree/'AndroidManifest.xml';xml=ET.parse(manifest);app=xml.getroot().find('application')
if app is None:raise SystemExit('Missing application manifest')
# Remove any old, openly accessible provider. Keep only our certificate-gated gateway.
for prov in list(app.findall('provider')):
    if prov.get('{'+ns+'}authorities') in ['lpa','ru.smsbridge.lpa']:app.remove(prov)
app.set('{'+ns+'}label','SMS Мост · адаптер')
ET.SubElement(app,'provider',{'{'+ns+'}name':'ru.smsbridge.adapter.GatewayProvider','{'+ns+'}authorities':'ru.smsbridge.lpa','{'+ns+'}exported':'true'})
xml.write(manifest,encoding='utf-8',xml_declaration=True)
run(['apktool','b',tree,'-o',work/'unsigned.apk'])
run([bt/'zipalign','-p','-f','4',work/'unsigned.apk',work/'aligned.apk'])
dist=base/'dist';dist.mkdir(exist_ok=True)
run([bt/'apksigner','sign','--ks',pathlib.Path(a.community)/'9eSIMCommunityKey.jks','--ks-pass','pass:147258369','--out',dist/'SMS-Most-Adapter.apk',work/'aligned.apk'])
run([bt/'apksigner','verify',dist/'SMS-Most-Adapter.apk'])
print('Companion built. Hardware compatibility and V0 key access are NOT verified by this build.')
