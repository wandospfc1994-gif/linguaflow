package com.linguaflow.app

import android.app.Application
import com.linguaflow.app.di.AppContainer
import com.linguaflow.app.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LinguaflowApp : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        container = DefaultAppContainer(this)

        CoroutineScope(Dispatchers.IO).launch {
            container.repository.initProgressIfNeeded()
            container.repository.initStatsIfNeeded()
            container.repository.createInitialConversationIfNeeded()
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashStore.write(this, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
