package com.retro.cartube.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class CarTubeSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return CarTubeScreen(carContext)
    }
}
