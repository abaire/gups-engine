# Instructor-owned shared libraries. Any package can load() these files:
#
#     load("//lib:quest.star", "quest")    # quests
#     load("//lib:shop.star", "shop")      # shops, currency and stock containers
#     load("//lib:trainer.star", "trainer")  # trainers and practice sessions
#     load("//lib:craft.star", "craft")    # crafting stations and recipes
#     load("//lib:ai.star", "ai")          # behavior trees and planning
#     load("//lib:pursuit.star", "cut_off")  # a fast hunter cutting its quarry off
#
# group.star exports nothing to load: its commands (group, invite, accept, gtell, ...) and hooks
# register in every world that includes lib.
#
# lib is a privileged package kind: it may register global commands, such as `quests`.

package(
    api_version = 1,
    kind = "lib",
    title = "Shared libraries",
)
