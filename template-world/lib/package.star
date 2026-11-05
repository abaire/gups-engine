# Instructor-owned shared libraries. Any package can load() these files:
#
#     load("//lib:quest.star", "quest")    # quests
#     load("//lib:shop.star", "shop")      # shops, currency and stock containers
#     load("//lib:ai.star", "ai")          # behavior trees and planning
#
# lib is a privileged package kind: it may register global commands, such as `quests`.

package(
    api_version = 1,
    kind = "lib",
    title = "Shared libraries",
)
