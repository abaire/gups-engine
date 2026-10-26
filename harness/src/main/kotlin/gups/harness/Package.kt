/**
 * Headless in-memory world, virtual clock, and the Starlark test runner behind `gups test`. The
 * fuzz walker (M6) and repro runner (M4) come later. Never depends on `net` or `persist`.
 */
package gups.harness
