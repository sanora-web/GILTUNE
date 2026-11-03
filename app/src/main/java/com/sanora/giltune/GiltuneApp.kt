package com.sanora.giltune

import android.app.Application

/** Loads assets/scene_rules.json once, before any activity or service runs. SANORA WEB */
class GiltuneApp : Application() {
    override fun onCreate() { super.onCreate(); SceneRules.ensure(this) }
}
