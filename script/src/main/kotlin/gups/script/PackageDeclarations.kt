package gups.script

import gups.core.WORLD_TICK_PULSES
import gups.core.model.Roll
import gups.core.world.Feature
import gups.core.world.Features

private val NAMESPACE_REGEX = Regex("[a-z0-9_]+")

/** Copies the validated arguments of `package(...)` onto [p]. */
internal fun fillPackage(p: ScriptPackage, d: ParsedDecl, features: Features) {
  p.kind =
    d.choice("kind")?.let(PackageKind::parse)
      ?: fail("package(): kind is required (zone, lib, rules or skills)")
  if (p.kind == PackageKind.SKILLS && Feature.SKILLS !in features) {
    fail(FeatureOwnership.refusal("package kind 'skills'", Feature.SKILLS))
  }
  p.title = d.str("title") ?: p.path
  p.resetTicks = resetTicks(d)
  p.sharedNamespaces = sharedNamespaces(d, p.kind)
  p.modifiers = modifiers(d["modifiers"], d.what("modifiers"), features)
  p.expectedTombstones = labels(d, "expected_tombstones", p.path)
  p.migrateNoop = labels(d, "migrate_noop", p.path)
}

/** Counted in world ticks so a reset always lands on a world-tick boundary. */
private fun resetTicks(d: ParsedDecl): Roll? =
  d["reset_world_ticks"]?.let { raw ->
    parseRoll(raw, d.what("reset_world_ticks"), 1).also {
      if (it.max > Int.MAX_VALUE / WORLD_TICK_PULSES) fail("package(reset_world_ticks): too large")
    }
  }

private fun sharedNamespaces(d: ParsedDecl, kind: PackageKind): List<String> {
  val namespaces = d.strList("shared_namespaces")
  if (namespaces.isNotEmpty() && !kind.privileged) {
    fail("package(shared_namespaces): only lib and rules packages may declare shared namespaces")
  }
  for (ns in namespaces) {
    if (!NAMESPACE_REGEX.matches(ns)) {
      fail("package(shared_namespaces): '$ns' is not a valid namespace")
    }
  }
  return namespaces
}

private fun labels(d: ParsedDecl, param: String, pkg: String) =
  d.strList(param).map { label(it, pkg, d.what(param)) }
