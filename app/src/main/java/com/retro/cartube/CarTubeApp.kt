package com.retro.cartube

import android.app.Application
import com.retro.cartube.lounge.YouTubeLoungeReceiver
import com.retro.cartube.player.CarTubePlaybackManager

class CarTubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CarTubePlaybackManager.initialize(this)
        YouTubeLoungeReceiver.start(this)
    }
}
