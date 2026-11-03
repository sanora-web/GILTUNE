package com.sanora.giltune

import java.io.File

/** Loads the same assets/scene_rules.json the app ships (JVM test, real org.json). */
object TestRules {
    val rules: SceneRules by lazy { SceneRules.parse(File("src/main/assets/scene_rules.json").readText()).also { SceneRules.install(it) } }
    fun load(): SceneRules = rules
}
fun S(id: String): Scene = TestRules.load().let { Scene.byId(id)!! }
