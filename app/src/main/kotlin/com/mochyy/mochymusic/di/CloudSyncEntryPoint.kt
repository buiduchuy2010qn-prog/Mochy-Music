package com.mochyy.mochymusic.di

import com.mochyy.mochymusic.db.MusicDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Cổng truy cập cơ sở dữ liệu dùng chung cho Mochy Cloud sync bên ngoài ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CloudSyncEntryPoint {
    fun database(): MusicDatabase
}
