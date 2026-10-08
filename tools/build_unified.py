#!/usr/bin/env python3
"""Build one APK from pinned OpenEUICC source and our SMS relay, without injecting binaries."""
import pathlib,subprocess,shutil,xml.etree.ElementTree as ET,tarfile,re,json
from patch_profile_delete import patch_service,patch_profile_read
from patch_task_recovery import patch_task_recovery
base=pathlib.Path(__file__).resolve().parents[1]
vendor=base/'vendor'/'openeuicc'
ref='f17e1713722b89da0234954d9beb81ec77c5005e'
def run(args,cwd=None):subprocess.run([str(x) for x in args],cwd=cwd,check=True)
if not vendor.exists():run(['git','clone','--no-checkout','https://github.com/estkme-group/openeuicc.git',vendor])
run(['git','checkout','--detach',ref],vendor)
run(['git','submodule','update','--init','--recursive'],vendor)
# Build this project directly from source. No unlicensed bridge implementation is included.
settings=vendor/'settings.gradle.kts';s=settings.read_text();s=re.sub(r'buildscript \{.*?\n\}\n','',s,flags=re.S);settings.write_text(s)
p=vendor/'app-deps/build.gradle.kts';s=p.read_text();s=re.sub(r'import org.lineageos[^\n]*\n','',s);s=re.sub(r'apply \{\s*plugin<GenerateBpPlugin>\(\)\s*\}\s*','',s);s=s[:s.find('configure<GenerateBpPluginExtension>')] if 'configure<GenerateBpPluginExtension>' in s else s;p.write_text(s)
# Ensure Java 17 relay sources and Kotlin use the same target in the application module.
p=vendor/'app-unpriv/build.gradle.kts';s=p.read_text().replace('applicationId = "im.angry.easyeuicc"','applicationId = "ru.smsbridge.app"\n        versionCode = 16\n        versionName = "0.16.0"\n        buildConfigField("String", "LPA_SIGNER_SHA256", "\\\"\\\"")')
s=s.replace('android {','android {\n    buildFeatures { buildConfig = true }',1)
s=s.replace('plugin<MyVersioningPlugin>()','').replace('JavaVersion.VERSION_1_8','JavaVersion.VERSION_17').replace('jvmTarget = "1.8"','jvmTarget = "17"')
s=s.replace('versionNameSuffix = "-unpriv"','versionNameSuffix = ""')
s=s.replace('    implementation(project(":app-common"))','''    implementation(project(":app-common"))
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.json:json:20240303")''')
p.write_text(s)
# Java Application inherits the upstream LPA container in the unified variant.
p=vendor/'app-unpriv/src/main/java/im/angry/openeuicc/UnprivilegedOpenEuiccApplication.kt';s=p.read_text().replace('class UnprivilegedOpenEuiccApplication','open class UnprivilegedOpenEuiccApplication');p.write_text(s)
java=vendor/'app-unpriv/src/main/java/ru/smsbridge/app';java.mkdir(parents=True,exist_ok=True)
for p in (base/'app/src/main/java/ru/smsbridge/app').glob('*.java'):
 s=p.read_text().replace('ru.smsbridge.app.R.','im.angry.easyeuicc.R.').replace('BuildConfig.LPA_SIGNER_SHA256','im.angry.easyeuicc.BuildConfig.LPA_SIGNER_SHA256')
 if p.name=='BridgeApp.java':s=s.replace('extends Application','extends im.angry.openeuicc.UnprivilegedOpenEuiccApplication')
 (java/p.name).write_text(s)
gateway=vendor/'app-unpriv/src/main/java/ru/smsbridge/adapter';gateway.mkdir(parents=True,exist_ok=True)
shutil.copy2(base/'adapter/GatewayProvider.kt',gateway/'GatewayProvider.kt')
common_service=vendor/'app-common/src/main/java/im/angry/openeuicc/service'
shutil.copy2(base/'adapter/ForegroundTaskQueue.kt',common_service/'ForegroundTaskQueue.kt')
common_tests=vendor/'app-common/src/test/java/im/angry/openeuicc/service';common_tests.mkdir(parents=True,exist_ok=True)
shutil.copy2(base/'adapter/tests/ForegroundTaskQueueTest.kt',common_tests/'ForegroundTaskQueueTest.kt')
p=vendor/'app-common/build.gradle.kts';s=p.read_text().replace('    testImplementation("junit:junit:4.13.2")','    testImplementation("junit:junit:4.13.2")\n    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")');p.write_text(s)
shutil.copytree(base/'app/src/main/res',vendor/'app-unpriv/src/main/res',dirs_exist_ok=True)
shutil.copytree(base/'app/src/test',vendor/'app-unpriv/src/test',dirs_exist_ok=True)
# Complete two upstream translations so lint checks stay enabled for this build.
for locale,toolkit,slot in [('ja','SIMツールキット','SIMツールキット #%d'),('zh','SIM 工具包','SIM 工具包 #%d')]:
 p=vendor/'app-unpriv/src/main/res'/('values-'+locale)/'sms_bridge_translations.xml';p.parent.mkdir(parents=True,exist_ok=True)
 p.write_text('<resources><string name="shortcut_sim_toolkit">'+toolkit+'</string><string name="shortcut_sim_toolkit_with_slot">'+slot+'</string></resources>')
ns='http://schemas.android.com/apk/res/android';tools='http://schemas.android.com/tools'
ET.register_namespace('android',ns);ET.register_namespace('tools',tools);a='{'+ns+'}'
p=vendor/'app-unpriv/src/main/AndroidManifest.xml';xml=ET.parse(p);root=xml.getroot();app=root.find('application')
app.set(a+'name','ru.smsbridge.app.BridgeApp');app.set(a+'label','SMS Мост');app.set(a+'icon','@drawable/ic_bridge');app.set(a+'roundIcon','@drawable/ic_bridge');app.set(a+'allowBackup','false');app.set(a+'usesCleartextTraffic','false')
for activity in app.findall('activity'):
 if activity.get(a+'name')=='im.angry.openeuicc.ui.UnprivilegedMainActivity':
  activity.set(a+'exported','false')
  for intent in list(activity.findall('intent-filter')):activity.remove(intent)
relay=ET.parse(base/'app/src/main/AndroidManifest.xml').getroot()
for permission in relay.findall('uses-permission'):
 if not any(n.get(a+'name')==permission.get(a+'name') for n in root.findall('uses-permission')):root.insert(0,permission)
for feature in relay.findall('uses-feature'):
 if not any(n.get(a+'name')==feature.get(a+'name') for n in root.findall('uses-feature')):root.insert(0,feature)
for item in relay.find('application'):
 if item.tag not in ('activity','service','receiver'):continue
 name=item.get(a+'name')
 if name.startswith('.'):item.set(a+'name','ru.smsbridge.app'+name)
 if item.tag=='activity':item.set(a+'theme','@style/AppTheme')
 app.append(item)
ET.SubElement(app,'provider',{a+'name':'ru.smsbridge.adapter.GatewayProvider',a+'authorities':'ru.smsbridge.lpa',a+'exported':'false'})
xml.write(p,encoding='utf-8',xml_declaration=True)
# Avoid indefinite HTTP operations while holding a SIM APDU channel.
p=vendor/'libs/lpac-jni/src/main/java/net/typeblog/lpac_jni/impl/HttpInterfaceImpl.kt';s=p.read_text().replace('conn.connectTimeout = 2000','conn.connectTimeout = 15000\n            conn.readTimeout = 45000');p.write_text(s)
# Test and build the exact unified Java + Kotlin sources, native lpac included.
p=vendor/'app-common/src/main/java/im/angry/openeuicc/service/EuiccChannelManagerService.kt';p.write_text(patch_task_recovery(patch_service(p.read_text())))
p=vendor/'libs/lpac-jni/src/main/jni/lpac-jni/lpac-jni.c';p.write_text(patch_profile_read(p.read_text()))
run(['python3',base/'tools/test_native_profile_read.py',p])
run(['bash','gradlew',':app-unpriv:assembleRelease',':app-unpriv:testReleaseUnitTest',':app-common:testReleaseUnitTest',':app-unpriv:lintRelease','--no-daemon'],vendor)
dist=base/'dist';dist.mkdir(exist_ok=True)
output=vendor/'app-unpriv/build/outputs/apk/release'
apks=list(output.glob('*.apk'));assert len(apks)==1,apks
shutil.copy2(apks[0],dist/'SMS-Most-Unified-unsigned.apk')
# Ship the full corresponding source including all pinned native submodules and changes.
with tarfile.open(dist/'SMS-Most-Unified-source.tar.gz','w:gz') as archive:
 for p in vendor.rglob('*'):
  if not p.is_file() or any(x in ('.git','.gradle','build','.cxx') for x in p.relative_to(vendor).parts):continue
  archive.add(p,arcname='SMS-Most-Unified-source/'+str(p.relative_to(vendor)))
shutil.copytree(vendor/'app-unpriv/build/reports',base/'build/unified-reports',dirs_exist_ok=True)
print('Unified source build complete; SIM hardware operations require device verification.')
