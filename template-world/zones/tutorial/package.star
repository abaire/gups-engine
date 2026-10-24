# The tutorial zone: a small, heavily commented reference zone. `gups serve --dev` loads it,
# and its tests in tutorial_test.star run on every engine commit, so everything here works.
#
# A package is a directory with a package.star. This one is addressed as //zones/tutorial.
# package.star holds exactly one call to package(); the content lives in the other .star
# files of the directory, which the engine loads in alphabetical order. Files ending in
# _test.star are tests, not content.

package(
    # The script API version this package is written against. The engine supports exactly
    # one version at a time; a mismatch stops the package from loading and names the gap.
    api_version = 1,

    # zone | lib | rules | skills. Zones hold rooms, mobs and items.
    kind = "zone",
    title = "The Tutorial",

    # Every 1200 pulses (5 minutes) the zone resets: its spawns refill and every room's
    # on_reset hook runs. See the armory in rooms.star.
    reset_pulses = 1200,
)
