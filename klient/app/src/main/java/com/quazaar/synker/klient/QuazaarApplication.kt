package com.quazaar.synker.klient

import android.app.Application
import com.quazaar.synker.klient.data.MusicDatabaseHelper
import com.quazaar.synker.klient.sync.QuazaarSyncManager

class QuazaarApplication : Application() {
    lateinit var databaseHelper: MusicDatabaseHelper
        private set

    lateinit var syncManager: QuazaarSyncManager
        private set

    companion object {
        lateinit var instance: QuazaarApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        databaseHelper = MusicDatabaseHelper(this)
        syncManager = QuazaarSyncManager.getInstance(this)
    }
}
