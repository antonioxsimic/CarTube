package com.retro.cartube.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.retro.cartube.lounge.YouTubeLoungeReceiver
import com.retro.cartube.player.CarTubePlaybackManager

class CarTubeCarService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        try {
            CarTubePlaybackManager.initialize(applicationContext)
            YouTubeLoungeReceiver.start(applicationContext)
        } catch (e: Exception) {
            android.util.Log.w("CarTubeCarService", "Error initializing playback manager or lounge", e)
        }
        return CarTubeSession()
    }

    override fun onDestroy() {
        android.util.Log.i("CarTubeCarService", "Android Auto CarAppService destroyed -> auto-pausing playback")
        try {
            CarTubePlaybackManager.pause()
        } catch (e: Exception) {
            android.util.Log.w("CarTubeCarService", "Error pausing on service destroy", e)
        }
        super.onDestroy()
    }
}
