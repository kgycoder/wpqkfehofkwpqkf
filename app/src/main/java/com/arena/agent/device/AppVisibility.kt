package com.arena.agent.device

import android.app.Activity

object AppVisibility {
    @Volatile
    var activity: Activity? = null
}
