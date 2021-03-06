package scodec.protocols
package mpeg
package transport
package psi

import scalaz.\/.{ right, left }
import scalaz.stream.Process

import scodec.Err
import scodec.bits._
import scodec.codecs._

class SectionCodecTest extends ProtocolsSpec {

  "the SectionCodec class" should {

    "support decoding a stream of packets in to a stream of sections" which {

      val sectionCodec = SectionCodec.supporting[ProgramAssociationSection]

      "handles case where section starts at beginning of packet and is fully contained within packet" in {
        val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true, Map(ProgramNumber(1) -> Pid(2)))).head
        val pasEnc = sectionCodec.encode(pas).require
        val packet = Packet.payload(Pid(0), ContinuityCounter(0), Some(0), pasEnc)

        val p = Process.emit(packet).toSource pipe Demultiplexer.demultiplex(sectionCodec)
        p.runLog.run shouldBe IndexedSeq(pas).map(s => PidStamped(Pid(0), right(Demultiplexer.SectionResult(s))))
      }

      "handles case where section starts at beginning of packet and spans multiple packets" in {
        val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true,
          (for (i <- 0 until ProgramAssociationTable.MaxProgramsPerSection)
          yield ProgramNumber(i) -> Pid(i)).toMap
        )).head
        val pasEnc = sectionCodec.encode(pas).require
        val packets = Packet.packetize(Pid(0), ContinuityCounter(0), pasEnc)

        val p = Process.emitAll(packets).toSource pipe Demultiplexer.demultiplex(sectionCodec)
        p.runLog.run shouldBe IndexedSeq(pas).map(s => PidStamped(Pid(0), right(Demultiplexer.SectionResult(s))))
      }

      "checks packet continuity" in {
        val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true,
          (for (i <- 0 until ProgramAssociationTable.MaxProgramsPerSection)
          yield ProgramNumber(i) -> Pid(i)).toMap
        )).head
        val pasEnc = sectionCodec.encode(pas).require
        val packets = Packet.packetize(Pid(0), ContinuityCounter(1), pasEnc)
        val withDiscontinuity = packets.updated(0, packets.head.copy(header = packets.head.header.copy(continuityCounter = ContinuityCounter(15))))

        val p = Process.emitAll(withDiscontinuity).toSource pipe Demultiplexer.demultiplex(sectionCodec)
        p.runLog.run shouldBe IndexedSeq(PidStamped(Pid(0), left(DemultiplexerError.Discontinuity(ContinuityCounter(15), ContinuityCounter(2)))))
      }

      "upon decoding failure of a section, remaining sections in packet are decoded" in {
        case class SmallSection(x: Int) extends Section { def tableId = 0 }
        val sections = Vector(SmallSection(0), SmallSection(1))

        implicit val sfc = SectionFragmentCodec.nonExtended[SmallSection, Int](0, h => (constant(bin"0") ~> uint(7)), (p, i) => SmallSection(i), ss => (bin"010", ss.x))
        val sc = SectionCodec.supporting[SmallSection]

        val encodedSections = sections map { s => sc.encode(s).require }
        val ss0 = encodedSections(0).bytes
        val ss1 = encodedSections(1).bytes
        val indexOfInt = ss0.toIndexedSeq.zipWithIndex.find { case (x, idx) => ss1(idx) != x }.map { case (x, idx) => idx }.get
        val ss255 = ss0.update(indexOfInt, 255.toByte)

        val packets = Packet.packetizeMany(Pid(0), ContinuityCounter(0), ss255.bits +: encodedSections)
        val p = Process.emitAll(packets).toSource pipe Demultiplexer.demultiplex(sc)

        p.runLog.run shouldBe (
          PidStamped(Pid(0), left(DemultiplexerError.Decoding(Err("expected constant BitVector(1 bits, 0x0) but got BitVector(1 bits, 0x8)")))) +:
          sections.map { x => PidStamped(Pid(0), right(Demultiplexer.SectionResult(x))) }
        )
      }

      "reports invalid CRC" in {
        val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true, Map(ProgramNumber(1) -> Pid(2)))).head
        val pasEnc = sectionCodec.encode(pas).require
        val corruptedSection = pasEnc.dropRight(32) ++ (~pasEnc.dropRight(32))
        val packet = Packet.payload(Pid(0), ContinuityCounter(0), Some(0), corruptedSection)
        val p = Process.emit(packet).toSource pipe Demultiplexer.demultiplex(sectionCodec)
        p.runLog.run shouldBe IndexedSeq(PidStamped(Pid(0), left(DemultiplexerError.Decoding(Err("CRC mismatch: calculated 18564404 does not equal -11537665")))))

      }

      "does not report invalid CRC when verifyCrc is disabled" in {
        val sectionCodec = SectionCodec.psi.disableCrcVerification.supporting[ProgramAssociationSection]
        val pas = ProgramAssociationTable.toSections(ProgramAssociationTable(TransportStreamId(1), 15, true, Map(ProgramNumber(1) -> Pid(2)))).head
        val pasEnc = sectionCodec.encode(pas).require
        val corruptedSection = pasEnc.dropRight(32) ++ (~pasEnc.dropRight(32))
        val packet = Packet.payload(Pid(0), ContinuityCounter(0), Some(0), corruptedSection)
        val p = Process.emit(packet).toSource pipe Demultiplexer.demultiplex(sectionCodec)
        p.runLog.run shouldBe IndexedSeq(pas).map(s => PidStamped(Pid(0), right(Demultiplexer.SectionResult(s))))

      }
    }
  }

}
