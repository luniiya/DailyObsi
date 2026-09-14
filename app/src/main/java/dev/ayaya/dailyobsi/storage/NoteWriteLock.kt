package dev.ayaya.dailyobsi.storage

import kotlinx.coroutines.sync.Mutex

/** Shared by in-app and widget read-modify-write operations in this process. */
val noteWriteMutex = Mutex()
