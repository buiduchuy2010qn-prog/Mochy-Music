package com.mochyy.mochymusic.db.entities

import com.mochyy.mochymusic.utils.reportException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal val EntitySyncScope: CoroutineScope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineExceptionHandler { _, throwable -> reportException(throwable) },
)
