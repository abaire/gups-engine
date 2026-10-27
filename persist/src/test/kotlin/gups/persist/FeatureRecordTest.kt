package gups.persist

import gups.core.world.Feature
import gups.core.world.Features
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureRecordTest {
  @Test
  fun `the same features match in any order`() {
    assertNull(
      FeatureRecord.mismatch("track,combat", Features(setOf(Feature.COMBAT, Feature.TRACK)))
    )
  }

  @Test
  fun `a saved feature this engine doesn't know is a mismatch`() {
    val why =
      assertNotNull(
        FeatureRecord.mismatch("combat,track,magic", Features(setOf(Feature.COMBAT, Feature.TRACK)))
      )
    assertContains(why, "unknown to this engine: magic")
  }
}
