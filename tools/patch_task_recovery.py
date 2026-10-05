"""Replace startup/completion coupling in the pinned LPA service with a tested task queue."""
def replace_once(source, old, new):
    assert source.count(old) == 1, 'Pinned task service anchor changed: '+old[:100]
    return source.replace(old, new)

def patch_task_recovery(source):
    source=replace_once(source,'        private const val TASK_FAILURE_ID = 1000',
        '        private const val TASK_FAILURE_ID = 1000\n        private const val TASK_START_ID = "smsbridge.adapter.task"')
    source=replace_once(source,'    private val wakeLock: PowerManager.WakeLock by lazy {',
        '    private val wakeLockDelegate = lazy {')
    source=replace_once(source,'    sealed interface ForegroundTaskState {',
        '    private val wakeLock: PowerManager.WakeLock by wakeLockDelegate\n\n    sealed interface ForegroundTaskState {')
    source=replace_once(source,'    private val foregroundStarted: MutableSharedFlow<Unit> = MutableSharedFlow()',
        '    private val taskQueue by lazy { ForegroundTaskQueue(lifecycleScope) }')
    source=replace_once(source,'''    private val foregroundTaskState: MutableStateFlow<ForegroundTaskState> =
        MutableStateFlow(ForegroundTaskState.Idle)''',
        '    private val foregroundTaskState get() = taskQueue.state')
    source=replace_once(source,'''    private val foregroundTaskSubscribers: MutableMap<Long, SharedFlow<ForegroundTaskState>> =
        mutableMapOf()''','')
    source=replace_once(source,'''            lifecycleScope.launch {
                foregroundStarted.emit(Unit)
            }''','''            intent?.takeIf { it.hasExtra(TASK_START_ID) }?.let {
                taskQueue.onStarted(it.getLongExtra(TASK_START_ID, -1L))
            }''')
    source=replace_once(source,'''        foregroundTaskSubscribers[taskId]?.let {
            ForegroundTaskSubscriberFlow(taskId, it.applyCompletionTransform())
        }''','        taskQueue.recover(taskId)')
    first=source.index('    private fun launchForegroundTask(')
    last=source.index('    suspend fun waitForForegroundTask()',first)
    replacement='''    private fun launchForegroundTask(
        title: String,
        failureTitle: String,
        iconRes: Int,
        task: suspend EuiccChannelManagerService.() -> Unit
    ): ForegroundTaskSubscriberFlow = taskQueue.launch(
        requestStart = { id ->
            startForegroundService(Intent(this, EuiccChannelManagerService::class.java).putExtra(TASK_START_ID, id))
        },
        prepare = {
            updateForegroundNotification(title, iconRes)
            wakeLock.acquire(10 * 60 * 1000L)
        },
        work = { this@EuiccChannelManagerService.task() },
        cleanup = {
            try {
                if (wakeLockDelegate.isInitialized() && wakeLock.isHeld) wakeLock.release()
            } finally {
                try { stopForeground(STOP_FOREGROUND_REMOVE) } finally { stopSelf() }
            }
        },
        progress = { updateForegroundNotification(title, iconRes) },
        failure = { error ->
            // Do not log ProfileDownloadException's raw HTTP/APDU payloads.
            Log.e(TAG, "Foreground task failed (" + error.javaClass.simpleName + ")")
            postForegroundTaskFailureNotification(failureTitle)
        }
    )

'''
    return source[:first]+replacement+source[last:]
