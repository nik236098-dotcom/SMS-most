"""Run the patched JNI profile-list function against read failure and empty/nonempty cards."""
import pathlib,re,subprocess,sys,tempfile
source=pathlib.Path(sys.argv[1]).read_text()
body=re.search(r'JNIEXPORT jlong JNICALL\nJava_net_typeblog_lpac_1jni_LpacJni_es10cGetProfilesInfo\([^\n]+\) \{.*?\n\}',source,re.S)
assert body, 'Profile-list JNI function missing'
harness=r'''
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#define JNIEXPORT
#define JNICALL
typedef intptr_t jlong;
typedef void *jobject;
typedef void *jclass;
struct JNIInterface;
typedef const struct JNIInterface *JNIEnv;
struct JNIInterface {
    jclass (*FindClass)(JNIEnv *,const char *);
    int (*ThrowNew)(JNIEnv *,jclass,const char *);
};
struct euicc_ctx { int unused; };
struct es10c_profile_info_list { int unused; };
static int result, thrown;
static struct es10c_profile_info_list *reply;
static jclass find_class(JNIEnv *env,const char *name) { return (void *)1; }
static int throw_new(JNIEnv *env,jclass type,const char *message) { thrown++;return 0; }
int es10c_get_profiles_info(struct euicc_ctx *ctx,struct es10c_profile_info_list **info) {
    *info=reply;return result;
}
'''
harness+=body.group(0)+r'''
int main(void) {
    const struct JNIInterface methods={find_class,throw_new};JNIEnv env=&methods;
    result=-1;reply=NULL;thrown=0;
    assert(Java_net_typeblog_lpac_1jni_LpacJni_es10cGetProfilesInfo(&env,NULL,0)==0 && thrown==1);
    result=0;reply=NULL;thrown=0;
    assert(Java_net_typeblog_lpac_1jni_LpacJni_es10cGetProfilesInfo(&env,NULL,0)==0 && thrown==0);
    struct es10c_profile_info_list profile;
    result=0;reply=&profile;thrown=0;
    assert(Java_net_typeblog_lpac_1jni_LpacJni_es10cGetProfilesInfo(&env,NULL,0)==(jlong)&profile && thrown==0);
    return 0;
}
'''
with tempfile.TemporaryDirectory() as temp:
    root=pathlib.Path(temp);c=root/'profile-read.c';binary=root/'profile-read';c.write_text(harness)
    subprocess.run(['cc','-std=c11','-Werror=implicit-function-declaration',str(c),'-o',str(binary)],check=True)
    subprocess.run([str(binary)],check=True)
print('Passed 3 native profile-read checks')
