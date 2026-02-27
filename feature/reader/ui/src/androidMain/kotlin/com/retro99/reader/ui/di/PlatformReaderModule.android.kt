package com.retro99.reader.ui.di

import org.koin.core.annotation.Module

/**
 * Android implementation of platform-specific Reader module.
 *
 * Note: ExoPlayer is no longer provided here. It is now owned by MediaPlaybackService
 * to ensure it survives ReaderScope lifecycle (needed for Android Auto playback).
 *
 * Components that need the ExoPlayer should get it from MediaPlaybackController.player.
 */
@Module
actual class PlatformReaderModule

