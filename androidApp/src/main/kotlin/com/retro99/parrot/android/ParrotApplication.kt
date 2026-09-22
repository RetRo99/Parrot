package com.retro99.parrot.android

import android.app.Application
import com.retro99.parrot.di.initKoin
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger

class ParrotApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        initKoin {
            androidLogger()
            androidContext(this@ParrotApplication)
        }
        SyncWorkScheduler.enqueue(this)
    }
}
