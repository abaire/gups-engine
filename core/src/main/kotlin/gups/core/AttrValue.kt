package gups.core

/**
 * A script-compatible attribute value stored in an entity's custom attributes.
 *
 * Supported Starlark types convert both ways:
 * - `int` maps to [I] (stored as 64-bit signed [Long]).
 * - `str` maps to [S].
 * - `bool` maps to [B].
 * - `list` maps to [L].
 * - string-keyed `dict` maps to [M].
 *
 * Setting an attribute to `None` deletes the attribute. Floats, functions, and host objects cannot
 * be stored and are rejected.
 *
 * Serialized custom attributes on an entity are limited to 64 KB measured as compact JSON. The
 * `core` module intentionally avoids dependencies on `kotlinx.serialization`; serialization to and
 * from JSON is handled directly by the persistence layer.
 */
sealed interface AttrValue {
  /** 64-bit integer attribute value corresponding to Starlark `int`. */
  data class I(val v: Long) : AttrValue

  /** String attribute value corresponding to Starlark `str`. */
  data class S(val v: String) : AttrValue

  /** Boolean attribute value corresponding to Starlark `bool`. */
  data class B(val v: Boolean) : AttrValue

  /** List of attribute values corresponding to Starlark `list`. */
  data class L(val v: List<AttrValue>) : AttrValue

  /** String-keyed map of attribute values corresponding to Starlark `dict`. */
  data class M(val v: Map<String, AttrValue>) : AttrValue
}
