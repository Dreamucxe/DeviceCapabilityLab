package com.devicelab

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The application.
 *
 * Nothing is initialised here beyond Hilt. In particular no scan is started: detection
 * touches the camera service, the audio HAL and real DRM plugin instances, and doing
 * that during application startup would delay first frame and run those queries even
 * when the user only opened the app to read a saved snapshot. The scan begins when the
 * dashboard asks for it.
 *
 * There is also no analytics, crash reporter or network client to initialise --
 * Section 26 requires the app to be entirely offline, and it holds no INTERNET
 * permission, so there is nothing it could talk to even by accident.
 */
@HiltAndroidApp
class DeviceLabApplication : Application()
