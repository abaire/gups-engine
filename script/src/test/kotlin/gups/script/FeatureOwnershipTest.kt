package gups.script

import gups.core.world.Feature
import gups.core.world.FeatureRequirement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FeatureOwnershipTest {
  @Test
  fun `of maps symbols to feature requirements`() {
    assertEquals(
      FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS),
      FeatureOwnership.of("rules:to_hit"),
    )
    assertEquals(FeatureRequirement.of(Feature.SKILLS), FeatureOwnership.of("rules:cast_allowed"))
    assertNull(FeatureOwnership.of("rules:mitigate"))
    assertEquals(FeatureRequirement.of(Feature.TRACK), FeatureOwnership.of("knockback.steps"))
  }

  @Test
  fun `refusal text produces expected forms`() {
    assertEquals(
      "something needs the combat feature",
      FeatureOwnership.refusal("something", FeatureRequirement.of(Feature.COMBAT)),
    )
    assertEquals(
      "something needs the combat or skills feature",
      FeatureOwnership.refusal(
        "something",
        FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS),
      ),
    )
    assertEquals(
      "something needs the combat and skills features",
      FeatureOwnership.refusal(
        "something",
        FeatureRequirement.allOf(Feature.COMBAT, Feature.SKILLS),
      ),
    )
  }
}
