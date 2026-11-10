package gups.persist

import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.model.Prototypes
import gups.core.world.AffectSpec
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.quit
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcPersistenceTest {
  @Test
  fun `affect with one proc per effect variant round trips through affectJson`() {
    val fire = DamageType("fire")
    val dummySkill = Label("//t:dummy_skill")
    val dummyHook = HookRef(Label("//t:pkg"), "on_tick")
    val nestedProc =
      Proc(
        on = ProcTrigger.STRUCK,
        chance = 50,
        outcomes =
          listOf(
            ProcOutcome(
              weight = 100,
              effect = Effect.Stun(1),
              target = ProcTarget.SELF,
              message = "Shock!",
            )
          ),
      )
    val applyAffectEffect =
      Effect.ApplyAffect(
        name = "shield",
        duration = 10,
        statDeltas = mapOf("str" to 2),
        flags = setOf("protected"),
        tickEvery = 5,
        onTick = dummyHook,
        onExpire = dummyHook,
        expireMessage = "Shield faded",
        modifiers = mapOf(ModifierPoint.TO_HIT to dummyHook),
        senseDeltas = emptyMap(),
        maxVitalDeltas = mapOf("hp" to 20),
        resistDeltas = mapOf(fire to 10),
        applies = AffectScope.WORN,
        procs = listOf(nestedProc),
      )

    val procs =
      listOf(
        Proc(
          ProcTrigger.HIT,
          10,
          listOf(ProcOutcome(100, effect = Effect.Damage(Dice(1, 6, 1), fire))),
        ),
        Proc(
          ProcTrigger.HIT,
          20,
          listOf(ProcOutcome(80, effect = Effect.Heal(Dice(2, 4), "hp"), target = ProcTarget.SELF)),
        ),
        Proc(ProcTrigger.HIT, 30, listOf(ProcOutcome(100, effect = applyAffectEffect))),
        Proc(
          ProcTrigger.AVOID,
          40,
          listOf(
            ProcOutcome(100, effect = Effect.Dispel("curse*", setOf("bad"), all = false, count = 2))
          ),
        ),
        Proc(ProcTrigger.STRUCK, 50, listOf(ProcOutcome(100, effect = Effect.Knockback(2)))),
        Proc(ProcTrigger.STRUCK, 60, listOf(ProcOutcome(100, effect = Effect.Pull(1)))),
        Proc(ProcTrigger.HIT, 70, listOf(ProcOutcome(100, effect = Effect.Stun(3)))),
        Proc(
          ProcTrigger.HIT,
          80,
          listOf(
            ProcOutcome(100, effect = Effect.Summon(Label("//t:minion"), count = 2, duration = 30))
          ),
        ),
        Proc(
          ProcTrigger.HIT,
          90,
          listOf(
            ProcOutcome(
              100,
              effect = Effect.Sequence(Effect.Damage(Dice(1, 4), fire), Effect.Knockback(1)),
            )
          ),
        ),
        Proc(
          ProcTrigger.STRUCK,
          100,
          listOf(
            ProcOutcome(
              100,
              effect =
                Effect.Plugin(
                  pluginId = "custom",
                  name = "drain",
                  args =
                    mapOf(
                      "amount" to 10L,
                      "ratio" to 0.5,
                      "flag" to true,
                      "tags" to listOf("a", "b"),
                      "nested" to mapOf("k" to 42L),
                    ),
                ),
            )
          ),
        ),
        Proc(
          ProcTrigger.HIT,
          25,
          listOf(ProcOutcome(50, skill = dummySkill, target = ProcTarget.ENEMY, message = "Boom!")),
        ),
      )

    val affect =
      Affect(
        id = 100L,
        name = "master_buff",
        source = EntityId(1),
        appliedAt = 10L,
        expiresAt = 200L,
        procs = procs,
      )

    val json = affectJson(affect)
    val restored = affectFromJson(json)

    assertEquals(affect.id, restored.id)
    assertEquals(affect.name, restored.name)
    assertEquals(affect.procs.size, restored.procs.size)
    assertEquals(affect.procs, restored.procs)
  }

  @Test
  fun `actor with timed affect holding a skill-outcome proc survives persist restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val dummySkill = Label("//t:spell")
    val proc =
      Proc(
        on = ProcTrigger.HIT,
        chance = 50,
        outcomes =
          listOf(
            ProcOutcome(
              weight = 100,
              skill = dummySkill,
              target = ProcTarget.ENEMY,
              message = "Magic burst!",
            )
          ),
      )
    a.w.applyAffect(ann, AffectSpec("surge", 100, procs = listOf(proc)))

    val surge = ann.affects.single { it.name == "surge" }
    assertEquals(1, surge.procs.size)
    assertEquals(dummySkill, surge.procs[0].outcomes[0].skill)

    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val ann2 = b.login("Ann")
    val surge2 = ann2.affects.single { it.name == "surge" }
    assertEquals(1, surge2.procs.size)
    assertEquals(ProcTrigger.HIT, surge2.procs[0].on)
    assertEquals(50, surge2.procs[0].chance)
    assertEquals(dummySkill, surge2.procs[0].outcomes[0].skill)
    assertEquals(ProcTarget.ENEMY, surge2.procs[0].outcomes[0].target)
    assertEquals("Magic burst!", surge2.procs[0].outcomes[0].message)
    b.close()
  }

  @Test
  fun `item instance affect with procs survives persist restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    val proc =
      Proc(
        on = ProcTrigger.STRUCK,
        chance = 75,
        outcomes =
          listOf(ProcOutcome(weight = 100, effect = Effect.Damage(Dice(1, 4), DamageType("fire")))),
      )
    a.w.applyItemAffect(
      gem,
      AffectSpec("thorns", 50, procs = listOf(proc)),
      applies = AffectScope.CARRIED,
    )

    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val ann2 = b.login("Ann")
    val gem2 = ann2.inventory.single()
    val thorns = gem2.affects.single { it.name == "thorns" }
    assertEquals(1, thorns.procs.size)
    assertEquals(ProcTrigger.STRUCK, thorns.procs[0].on)
    assertEquals(75, thorns.procs[0].chance)
    assertTrue(thorns.procs[0].outcomes[0].effect is Effect.Damage)
    b.close()
  }

  @Test
  fun `prototype procs are not written in item payload json`() {
    val fireDagger =
      ItemProto(
        Label("//t:fire_dagger"),
        "fire dagger",
        listOf("dagger"),
        procs =
          listOf(
            Proc(
              on = ProcTrigger.HIT,
              chance = 100,
              outcomes =
                listOf(ProcOutcome(100, effect = Effect.Damage(Dice(1, 4), DamageType("fire")))),
            )
          ),
      )
    val base = P.protos()
    val protos =
      Prototypes(
        rooms = base.rooms.values.toList(),
        mobs = base.mobs.values.toList(),
        items = base.items.values.filterNot { it.label == Label("//engine:corpse") } + fireDagger,
        zones = base.zones.values.toList(),
      )
    val file = tempDb()
    val a = PersistWorld(file, protos)
    val ann = a.login("Ann")
    val dagger = a.w.spawnItem(fireDagger.label, ItemLocation.Carried(ann.id))

    val row = a.w.encode(dagger)!!
    assertFalse(row.payload.contains("\"procs\""))
    a.close()
  }
}
