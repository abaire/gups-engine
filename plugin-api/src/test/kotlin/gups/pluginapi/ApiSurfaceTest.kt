package gups.pluginapi

import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** No public type in `gups.pluginapi` mentions a `core` or `script` type. */
class ApiSurfaceTest {
  private val classes =
    listOf(
      PluginApi::class,
      GupsPlugin::class,
      PluginRegistry::class,
      PluginRefused::class,
      PluginTickCall::class,
      PluginEventCall::class,
      PluginLane::class,
      PluginRole::class,
      PluginParam::class,
      PluginCommandCall::class,
      PluginGmcpCall::class,
      PluginHookCall::class,
      PluginEffectCall::class,
      PluginEffect::class,
      PluginOption::class,
      PluginChoice::class,
      PluginCreation::class,
      PluginTrait::class,
      PluginModifierPoint::class,
      PluginModifierCall::class,
      PluginModifier::class,
      PluginRestrictionPoint::class,
      PluginRestrictionCall::class,
      PluginGmcpWorld::class,
      PluginWorld::class,
      PluginRng::class,
      PluginEntity::class,
      PluginRoom::class,
      PluginActor::class,
      PluginItem::class,
      PluginItemAffect::class,
      PluginExit::class,
      PluginDoor::class,
      PluginStandingTotals::class,
      PluginContainmentCall::class,
      PluginSkill::class,
      PluginGroup::class,
      PluginPlanAction::class,
      PluginViews::class,
      PluginTargetChoice::class,
      PluginTargetChoice.Pick::class,
      PluginTargetOrderCall::class,
      PluginMemory::class,
    )

  private fun names(t: Type): List<String> =
    when (t) {
      is Class<*> -> listOf(t.name) + (t.componentType?.let(::names) ?: emptyList())
      is ParameterizedType -> names(t.rawType) + t.actualTypeArguments.flatMap(::names)
      is WildcardType -> (t.upperBounds + t.lowerBounds).flatMap(::names)
      else -> listOf(t.typeName)
    }

  @Test
  fun `the public api uses only plugin-api, Kotlin, Java and Starlark types`() {
    val leaks = mutableListOf<String>()
    fun check(where: String, t: Type) {
      for (n in names(t)) if (n.startsWith("gups.core.") || n.startsWith("gups.script."))
        leaks += "$where: $n"
    }
    for (k in classes) {
      val c = k.java
      assertEquals("gups.pluginapi", c.packageName, c.name)
      for (m in c.declaredMethods.filter { Modifier.isPublic(it.modifiers) }) {
        check("${c.simpleName}.${m.name} return", m.genericReturnType)
        m.genericParameterTypes.forEach { check("${c.simpleName}.${m.name} param", it) }
      }
      // Properties are checked through their public getters.
    }
    assertTrue(leaks.isEmpty(), leaks.joinToString("\n"))
  }
}
