package com.jarvis.assistant

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.security.SecurityManager
import com.jarvis.assistant.wake.WakeContract
import com.jarvis.assistant.wake.WakeStatus
import com.jarvis.assistant.audio.VoskModelManager
import com.jarvis.assistant.automation.command.CommandExecutor
import com.jarvis.assistant.automation.contacts.ContactResolver
import com.jarvis.assistant.automation.phone.PhoneControlManager
import com.jarvis.assistant.automation.whatsapp.WhatsAppAssistant
import com.jarvis.assistant.automation.youtube.YouTubeAutomationManager
import com.jarvis.assistant.data.memory.MemoryDatabase
import com.jarvis.assistant.data.memory.MemoryManager
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.backup.BackupCoordinator
import com.jarvis.assistant.data.preferences.AppPreferences
import com.jarvis.assistant.data.preferences.YouTubePreferences
import com.jarvis.assistant.data.repository.ChatRepository
import com.jarvis.assistant.mcp.McpManager
import com.jarvis.assistant.system.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class JarvisApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var preferences: AppPreferences
        private set

    lateinit var chatRepository: ChatRepository
        private set

    lateinit var memoryDatabase: MemoryDatabase
        private set

    lateinit var memoryRepository: MemoryRepository
        private set

    lateinit var memoryManager: MemoryManager
        private set

    lateinit var youTubePreferences: YouTubePreferences
        private set

    lateinit var youTubeAutomationManager: YouTubeAutomationManager
        private set

    lateinit var contactResolver: ContactResolver
        private set

    lateinit var phoneControlManager: PhoneControlManager
        private set

    lateinit var whatsAppAssistant: WhatsAppAssistant
        private set

    lateinit var commandExecutor: CommandExecutor
        private set

    lateinit var shizukuManager: ShizukuManager
        private set

    lateinit var mcpManager: McpManager
        private set

    lateinit var backupCoordinator: BackupCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Part 1 runs in the isolated `:wake` process. It only needs the
        // lightweight preferences; Room, TTS, Gemini, MCP, Shizuku, backup and
        // the 68 MB Vosk ASR model must never be loaded there.
        val wakeProcess = isWakeProcess()
        preferences = AppPreferences(this, lightweight = wakeProcess)
        if (wakeProcess) {
            Log.i(TAG, "Lightweight :wake process initialised; heavy subsystems skipped")
            return
        }

        SecurityManager.verify(this)
        registerWakeStatusReceiver()
        chatRepository = ChatRepository(preferences)

        // Memory System
        memoryDatabase = MemoryDatabase.getInstance(this)
        memoryRepository = MemoryRepository(memoryDatabase.memoryDao())
        memoryManager = MemoryManager(memoryRepository, preferences, chatRepository, appScope)

        // Automation Systems
        youTubePreferences = YouTubePreferences(this)
        youTubeAutomationManager = YouTubeAutomationManager(this, youTubePreferences)
        contactResolver = ContactResolver(this)
        phoneControlManager = PhoneControlManager(this, contactResolver)
        whatsAppAssistant = WhatsAppAssistant(this, contactResolver)

        // Shizuku + MCP extensions. Both are additive: if Shizuku is absent or
        // permission is denied, the rest of Jarvis keeps working normally.
        shizukuManager = ShizukuManager(this)
        mcpManager = McpManager(this, shizukuManager)

        // Uninstall-surviving snapshot: memories, chat history, personal
        // preferences, API keys and custom MCP servers. Restore runs on the
        // first launch after a reinstall; writes are throttled and also happen
        // when the app goes to the background.
        backupCoordinator = BackupCoordinator(
            context = this,
            preferences = preferences,
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            memoryManager = memoryManager,
            youTubePreferences = youTubePreferences,
            mcpManager = mcpManager,
            scope = appScope
        )
        backupCoordinator.start()

        // Command Engine
        commandExecutor = CommandExecutor(
            phoneManager = phoneControlManager,
            youtubeManager = youTubeAutomationManager,
            whatsappAssistant = whatsAppAssistant,
            memoryManager = memoryManager
        )

        VoskModelManager.init(this, CoroutineScope(Dispatchers.Default))

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedActivities = 0
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
                com.jarvis.assistant.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                if (startedActivities == 1) _isAppForeground.value = true
            }
            override fun onActivityResumed(activity: android.app.Activity) {
                currentResumedActivity = activity
                com.jarvis.assistant.util.ThemeManager.applyTheme(activity)
            }
            override fun onActivityPaused(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) {
                    _isAppForeground.value = false
                    if (::backupCoordinator.isInitialized) {
                        backupCoordinator.requestImmediateBackup("background")
                    }
                }
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {
                if (currentResumedActivity === activity) {
                    currentResumedActivity = null
                }
            }
        })
    }

    var currentResumedActivity: android.app.Activity? = null
        private set

    fun moveAppToBack(): Boolean {
        val act = currentResumedActivity ?: return false
        return try {
            act.moveTaskToBack(true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True when this application object belongs to the `:wake` process.
     *
     * `Application.getProcessName()` is API 28+; on older devices the process
     * name is read from /proc/self/cmdline.
     */
    private fun isWakeProcess(): Boolean {
        val processName = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                File("/proc/self/cmdline").readText().trim('\u0000', '\n', ' ')
            }
        } catch (_: Throwable) {
            packageName
        }
        return processName.endsWith(":wake")
    }

    /**
     * Part 1 lives in `:wake`, so its StateFlow cannot be read directly here.
     * It broadcasts every transition; mirror that into [WakeStatus] for the UI
     * and the assist service.
     */
    private fun registerWakeStatusReceiver() {
        try {
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != WakeContract.ACTION_STATE_CHANGED) return
                        WakeStatus.set(
                            running = intent.getBooleanExtra(WakeContract.EXTRA_RUNNING, false),
                            listening = intent.getBooleanExtra(WakeContract.EXTRA_LISTENING, false)
                        )
                    }
                },
                IntentFilter(WakeContract.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to register wake status receiver: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "JarvisApp"

        private val _isAppForeground = MutableStateFlow(false)
        val isAppForeground: StateFlow<Boolean> = _isAppForeground.asStateFlow()

        lateinit var instance: JarvisApp
            private set
    }
}

