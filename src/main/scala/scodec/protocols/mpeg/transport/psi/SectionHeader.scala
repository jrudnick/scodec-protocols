package scodec.protocols.mpeg
package transport
package psi

import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._
import shapeless.Iso

case class SectionHeader(
  tableId: Int,
  extendedSyntax: Boolean,
  privateBits: BitVector,
  length: Int)

object SectionHeader {

  implicit val iso = Iso.hlist(SectionHeader.apply _, SectionHeader.unapply _)

  implicit val codec: Codec[SectionHeader] = {
    ("table_id"                 | uint16      ) ::
    ("section_syntax_indicator" | bool        ) ::
    ("private_bits"             | bits(3)     ) ::
    ("length"                   | uint(12)    )
  }.as[SectionHeader]
}
