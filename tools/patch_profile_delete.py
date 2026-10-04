"""Bind bot deletion and its preceding disable to the confirmed EID inside the LPA queue."""
def patch_service(source):
    old = '        iccid: String\n    ): ForegroundTaskSubscriberFlow ='
    assert source.count(old) == 1, 'Pinned delete signature changed'
    source = source.replace(old, '        iccid: String,\n        expectedEid: String? = null\n    ): ForegroundTaskSubscriberFlow =')
    old = '                    channel.lpa.deleteProfile(iccid)'
    assert source.count(old) == 1, 'Pinned delete operation changed'
    source = source.replace(old, '''                    if (expectedEid != null) {
                        check(channel.lpa.eID == expectedEid) { "Adapter changed" }
                        check(channel.lpa.profiles.any {
                            it.iccid == iccid && it.state == net.typeblog.lpac_jni.LocalProfileInfo.State.Disabled
                        }) { "Profile missing or became active" }
                        check(channel.lpa.deleteProfile(iccid)) { "Card refused deletion" }
                    } else {
                        channel.lpa.deleteProfile(iccid)
                    }''')
    old = '        reconnectTimeoutMillis: Long = 0 // 0 = do not wait for reconnect'
    assert source.count(old) == 1, 'Pinned switch signature changed'
    source = source.replace(old, '        reconnectTimeoutMillis: Long = 0, // 0 = do not wait for reconnect\n        expectedEid: String? = null')
    old = '                        val refresh = preferenceRepository.refreshAfterSwitchFlow.first()'
    assert source.count(old) == 1, 'Pinned switch operation changed'
    source = source.replace(old, '                        if (expectedEid != null)check(channel.lpa.eID == expectedEid) { "Adapter changed" }\n'+old)
    return source

def patch_profile_read(source):
    # Upstream maps both read failure and a genuinely empty card to a null list.
    # A failed read must never be treated as proof that deletion succeeded.
    old = '''    if (es10c_get_profiles_info(ctx, &info) < 0) {
        return 0;
    }'''
    assert source.count(old) == 1, 'Pinned profile-list JNI changed'
    return source.replace(old, '''    if (es10c_get_profiles_info(ctx, &info) < 0) {
        jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (exception != NULL) {
            (*env)->ThrowNew(env, exception, "Unable to read eSIM profile list");
        }
        return 0;
    }''')
