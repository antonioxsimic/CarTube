package com.retro.cartube.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.retro.cartube.player.CarTubePlaybackManager

class CarTubeSession : Session(), DefaultLifecycleObserver {

    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(this)
        return CarTubeScreen(carContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        android.util.Log.i("CarTubeSession", "Car session destroyed (cable unplugged / car turned off) -> auto-pausing playback")
        CarTubePlaybackManager.pause()
    }
}
