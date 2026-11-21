package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.firestoreSettings

object FirestoreDatabaseProvider {
    private const val OFFLINE_CACHE_SIZE_BYTES = 100L * 1024L * 1024L

    @Volatile
    private var configuredDatabaseId: String? = null

    fun getInstance(context: Context): FirebaseFirestore {
        val appContext = context.applicationContext
        val databaseId = appContext.getString(R.string.firestore_database_id)
        val db = FirebaseFirestore.getInstance(databaseId)
        if (configuredDatabaseId != databaseId) {
            synchronized(this) {
                if (configuredDatabaseId != databaseId) {
                    runCatching {
                        db.firestoreSettings = firestoreSettings {
                            setLocalCacheSettings(
                                PersistentCacheSettings.newBuilder()
                                    .setSizeBytes(OFFLINE_CACHE_SIZE_BYTES)
                                    .build()
                            )
                        }
                    }
                    configuredDatabaseId = databaseId
                }
            }
        }
        return db
    }
}
