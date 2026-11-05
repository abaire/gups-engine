package gups.core.world

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Affect
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.RoomProto
import gups.core.model.Trait
import gups.core.model.TraitModifier
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomAffectsChainTest {
  private val hall =
    RoomProto(Label("//t:hall"), "The Hall", "A long hall.", tags = setOf("holy", "sealed"))

  @Test
  fun `effective tags add and remove live affect tags, and removal wins`() {
    val room = Room(EntityId(1), hall)
    room.affects +=
      Affect(100, "ward", null, 0, 10, addTags = setOf("warded"), removeTags = setOf("sealed"))
    assertEquals(setOf("holy", "warded"), room.tagsAt(0))
    room.affects +=
      Affect(101, "both", null, 0, 10, addTags = setOf("holy"), removeTags = setOf("holy"))
    assertEquals(setOf("warded"), room.tagsAt(0), "a tag that is both added and removed is removed")
    assertEquals(setOf("holy", "sealed"), room.tagsAt(10), "an affect is not live at its expiresAt")
    room.affects += Affect(102, "later", null, 20, 40, addTags = setOf("future"))
    assertEquals(setOf("holy", "sealed"), room.tagsAt(19), "not applied yet")
    assertEquals(setOf("holy", "sealed", "future"), room.tagsAt(20))
  }

  @Test
  fun `room affects run after the room prototype and before traits, in id order, with their own affect visible to the modifier`() {
    val host = RecordingHost()
    val seen = mutableListOf<String>()
    host.modifiers["ra"] = { _, c, base ->
      seen += c.affect?.name.orEmpty()
      (base as Int) + 2
    }
    host.modifiers["rb"] = { _, c, base ->
      seen += c.affect?.name.orEmpty()
      (base as Int) + 3
    }
    host.modifiers["room"] = { _, _, base -> (base as Int) + 1 }
    val f =
      Fixture(
        rules = FixedRules(toHit = 50),
        scripts = host,
        protos =
          Prototypes(
            listOf(hall.copy(modifiers = mapOf(ModifierPoint.TO_HIT to mod("room")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    f.w.traits =
      mapOf(
        "zealous" to
          Trait(
            "zealous",
            modifiers =
              mapOf(ModifierPoint.TO_HIT to TraitModifier { _, base -> (base as Int) * 2 }),
          )
      )
    val p = f.player("Ann")
    f.w.grantTrait(p, "zealous")
    val room = f.w.room(T.HALL)
    room.affects +=
      Affect(11, "rb", null, 0, 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("rb")))
    room.affects +=
      Affect(10, "ra", null, 0, 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("ra")))
    host.calls.clear()
    val r = f.w.toHitChain(RulesContext(f.w, p, f.mob(T.DUMMY)))
    assertEquals(112, r.value)
    assertEquals(
      listOf("mod:room:hall:room", "mod:room-affect:ra:ra", "mod:room-affect:rb:rb"),
      host.calls,
    )
    assertEquals(listOf("ra", "rb"), seen)
    assertEquals(
      "rules.to_hit base=50 → room:hall 51 → room-affect:ra 53 → room-affect:rb 56 → trait:zealous 112",
      r.text,
    )
  }
}
