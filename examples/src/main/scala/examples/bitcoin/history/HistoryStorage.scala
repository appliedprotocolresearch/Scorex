package examples.bitcoin.history

import com.google.common.primitives.Longs
import examples.commons.idToBAW
import examples.bitcoin.blocks._
import examples.bitcoin.mining.BitcoinMiningSettings
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.consensus.ModifierSemanticValidity
import scorex.core.consensus.ModifierSemanticValidity.{Absent, Unknown}
import scorex.crypto.hash.Sha256
import scorex.util.{ModifierId, ScorexLogging, bytesToId, idToBytes}

import scala.util.{Failure, Random, Try}

//TODO: why we are using IODB if there's no rollback?
class HistoryStorage(storage: LSMStore,
                     settings: BitcoinMiningSettings) extends ScorexLogging {

  private val bestPowIdKey = ByteArrayWrapper(Array.fill(storage.keySize)(-1: Byte))

  def height: Long = heightOf(bestPowId).getOrElse(0L)

  def bestChainScore: Long = height

  def bestPowId: ModifierId = storage.get(bestPowIdKey).map(d => bytesToId(d.data))
    .getOrElse(settings.GenesisParentId)

  // TODO: review me .get
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def bestPowBlock: PowBlock = {
    require(height > 0, "History is empty")
    modifierById(bestPowId).get.asInstanceOf[PowBlock]
  }

  def modifierById(blockId: ModifierId): Option[BitcoinBlock] = {
    storage.get(ByteArrayWrapper(idToBytes(blockId))).flatMap { bw =>
      val bytes = bw.data
      val mtypeId = bytes.head
      val parsed: Try[BitcoinBlock] = mtypeId match {
        case t: Byte if t == PowBlock.ModifierTypeId =>
          PowBlockCompanion.parseBytes(bytes.tail)
      }
      parsed match {
        case Failure(e) => log.warn("Failed to parse bytes from bd", e)
        case _ =>
      }
      parsed.toOption
    }
  }


  def semanticValidity(id: ModifierId): ModifierSemanticValidity = {
    modifierById(id).map { b =>
      storage
        .get(validityKey(b))
        .map(_.data.head)
        .map(ModifierSemanticValidity.restoreFromCode)
        .getOrElse(Unknown)
    }.getOrElse(Absent)
  }

  def updateValidity(b: BitcoinBlock, status: ModifierSemanticValidity): Unit = {
    val version = ByteArrayWrapper(Sha256(scala.util.Random.nextString(20).getBytes("UTF-8")))
    storage.update(version, Seq(), Seq(validityKey(b) -> ByteArrayWrapper(Array(status.code))))
  }

  /*
  For each block, it stores a key,value pair for its height, difficulty and bestPowIdKey (if the block is the best)
   */
  def update(b: BitcoinBlock, difficulty: Option[BigInt], isBest: Boolean): Unit = {
    log.debug(s"Write new best=$isBest block ${b.encodedId}")
    val typeByte = b match {
      case _: PowBlock =>
        PowBlock.ModifierTypeId
    }

    val blockH: Iterable[(ByteArrayWrapper, ByteArrayWrapper)] =
      Seq(blockHeightKey(b.id) -> ByteArrayWrapper(Longs.toByteArray(parentHeight(b) + 1)))

    val blockDiff: Iterable[(ByteArrayWrapper, ByteArrayWrapper)] = difficulty.map { d =>
      Seq(blockDiffKey(b.id) -> ByteArrayWrapper(d.toByteArray))
    }.getOrElse(Seq())

    val bestBlockSeq: Iterable[(ByteArrayWrapper, ByteArrayWrapper)] = b match {
      case powBlock: PowBlock if isBest =>
        Seq(bestPowIdKey -> idToBAW(powBlock.id))
      case _ => Seq()
    }

    storage.update(
      ByteArrayWrapper(Random.nextString(20).getBytes),
      Seq(),
      blockDiff ++
        blockH ++
        bestBlockSeq ++
        Seq(idToBAW(b.id) -> ByteArrayWrapper(typeByte +: b.bytes)))
  }

  /*
  Returns difficulty of mining a block over the given block (or the current best block)
   */
  // TODO: review me .get
  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def getPoWDifficulty(idOpt: Option[ModifierId]): BigInt = {
    idOpt match {
      case Some(id) if id == settings.GenesisParentId =>
        settings.initialDifficulty
      case Some(id) =>
        BigInt(storage.get(blockDiffKey(id)).get.data)
      case None if height > 0 =>
        BigInt(storage.get(blockDiffKey(bestPowId)).get.data)
      case _ =>
        settings.initialDifficulty
    }
  }


  def heightOf(blockId: ModifierId): Option[Long] = storage.get(blockHeightKey(blockId))
    .map(b => Longs.fromByteArray(b.data))

  def parentHeight(b: BitcoinBlock): Long = heightOf(parentId(b)).getOrElse(0L)

  def parentId(block: BitcoinBlock): ModifierId = block match {
    case powBlock: PowBlock => powBlock.parentId //
  }

  /*
  For a given block (or id), the below three functions create keys for different parameters of the block.
   */
  private def validityKey(b: BitcoinBlock): ByteArrayWrapper =
    ByteArrayWrapper(Sha256(s"validity${b.id}"))

  private def blockHeightKey(blockId: ModifierId): ByteArrayWrapper =
    ByteArrayWrapper(Sha256(s"height$blockId"))

  private def blockDiffKey(blockId: ModifierId): ByteArrayWrapper = {
    ByteArrayWrapper(Sha256(s"difficulties$blockId"))
  }

  def isGenesis(b: BitcoinBlock): Boolean = b match {
    case powB: PowBlock => powB.parentId == settings.GenesisParentId
  }

  def getAllBlocks: Seq[BitcoinBlock] = {
    storage.getAll().flatMap { kv =>
      val bw = kv._2
      val bytes = bw.data
      val mtypeId = bytes.head
//      val isBlock = mtypeId == PowBlock.ModifierTypeId
      val parsed: Option[BitcoinBlock] = mtypeId match {
        case t: Byte if t == PowBlock.ModifierTypeId =>
          PowBlockCompanion.parseBytes(bytes.tail).toOption
        case _ => None
      }
      parsed
    }.toSeq
  }
}