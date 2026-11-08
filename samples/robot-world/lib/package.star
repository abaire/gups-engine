# Shared libraries for the robot world. lib is a privileged package kind: it may register
# global hooks, such as skill_gain.star's on_skill_use.
package(
    api_version = 1,
    kind = "lib",
    title = "Shared libraries",
)
