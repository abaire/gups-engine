package gups.core.world

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureRequirementTest {
  @Test
  fun `any of is satisfied by either feature and fails with neither`() {
    val req = FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS)
    assertTrue(req.satisfiedBy(Features(setOf(Feature.COMBAT))))
    assertTrue(req.satisfiedBy(Features(setOf(Feature.SKILLS))))
    assertTrue(req.satisfiedBy(Features(setOf(Feature.COMBAT, Feature.SKILLS))))
    assertFalse(req.satisfiedBy(Features(emptySet())))
    assertFalse(req.satisfiedBy(Features(setOf(Feature.LEVELING))))
  }

  @Test
  fun `all of needs both features`() {
    val req = FeatureRequirement.allOf(Feature.COMBAT, Feature.SKILLS)
    assertFalse(req.satisfiedBy(Features(setOf(Feature.COMBAT))))
    assertFalse(req.satisfiedBy(Features(setOf(Feature.SKILLS))))
    assertTrue(req.satisfiedBy(Features(setOf(Feature.COMBAT, Feature.SKILLS))))
    assertFalse(req.satisfiedBy(Features(emptySet())))
  }

  @Test
  fun `text and ids follow declaration order whatever order arguments come in`() {
    val anyReq = FeatureRequirement.anyOf(Feature.SKILLS, Feature.COMBAT)
    assertEquals(listOf("combat", "skills"), anyReq.ids)
    assertEquals("combat or skills", anyReq.text)

    val allReq = FeatureRequirement.allOf(Feature.SKILLS, Feature.COMBAT)
    assertEquals(listOf("combat", "skills"), allReq.ids)
    assertEquals("combat and skills", allReq.text)

    val singleReq = FeatureRequirement.of(Feature.COMBAT)
    assertEquals(listOf("combat"), singleReq.ids)
    assertEquals("combat", singleReq.text)
  }

  @Test
  fun `noun is plural only when several features are all needed`() {
    assertEquals("feature", FeatureRequirement.of(Feature.COMBAT).noun)
    assertEquals("feature", FeatureRequirement.allOf(Feature.COMBAT).noun)
    assertEquals("feature", FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS).noun)
    assertEquals("features", FeatureRequirement.allOf(Feature.COMBAT, Feature.SKILLS).noun)
  }

  @Test
  fun `empty set is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { FeatureRequirement(emptySet()) }
  }
}
