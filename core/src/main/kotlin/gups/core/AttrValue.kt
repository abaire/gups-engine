package gups.core

/** A script-compatible attribute value. */
sealed interface AttrValue {
  data class I(val v: Long) : AttrValue

  data class S(val v: String) : AttrValue

  data class B(val v: Boolean) : AttrValue

  data class L(val v: List<AttrValue>) : AttrValue

  data class M(val v: Map<String, AttrValue>) : AttrValue
}
