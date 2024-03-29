package bitcoin.deprecated

import examples.bitcoin.blocks.PowBlock
import examples.bitcoin.history.BitcoinHistory
import org.scalatest.PropSpec
import Generator.randomPowBlockGenerator
import bitcoin.BitcoinGenerators
import scorex.crypto.hash.Blake2b256
import scorex.util.{ModifierId, bytesToId}

import scala.util.{Failure, Success}

class HistoryTest extends PropSpec with BitcoinGenerators {
//  val userConfigPath = "src/main/resources/settings.conf" // whether use this or above path?

//  override val settings: BitcoinSettings = BitcoinSettings.read(Some(userConfigPath))

  ignore("should get a history height==1") {
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val bitcoinHistory: BitcoinHistory = historyGen.sample.get
    assert(bitcoinHistory.height == 1)
  }

  property("wrong parent id should fail") {
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    var bitcoinHistory: BitcoinHistory = historyGen.sample.get
    val genesisBlock: PowBlock = bitcoinHistory.bestPowBlock
    val t0 = genesisBlock.timestamp
    var length = 1
    for (i <- 1 to length) {
      val block: PowBlock = randomPowBlockGenerator(bytesToId(Blake2b256(bitcoinHistory.bestPowId)), t0 + i * 1000)
      bitcoinHistory.append(block) match {
        case Success((history, progressInfo)) =>
          bitcoinHistory = history
        case Failure(e) =>
          println(e)
      }
    }
  }
  ignore("longest chain should win") {
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    var bitcoinHistory: BitcoinHistory = historyGen.sample.get
    val genesisBlock: PowBlock = bitcoinHistory.bestPowBlock
    val t0 = genesisBlock.timestamp
    val blockInterval = 2
    var length = 19
    for (i <- 1 to length) {
      val block: PowBlock = randomPowBlockGenerator(bitcoinHistory.bestPowId, t0 + blockInterval * i * 1000 )
      bitcoinHistory.append(block) match {
        case Success((history, progressInfo)) =>
          bitcoinHistory = history
        case Failure(e) =>
          println(e)
      }
    }
    assert(bitcoinHistory.height == length + 1)
    length = 20
    var fakeBestPowId = genesisBlock.id
    for (i <- 1 to length) {
      val block: PowBlock = randomPowBlockGenerator(fakeBestPowId, t0 + blockInterval * i * 100)
      bitcoinHistory.append(block) match {
        case Success((history, progressInfo)) =>
          bitcoinHistory = history
        case Failure(e) =>
          println(e)
      }
      fakeBestPowId = block.id
    }
    assert(bitcoinHistory.height == length + 1)
    assert(bitcoinHistory.bestPowId == fakeBestPowId)
    val recordBestPowId = fakeBestPowId
    fakeBestPowId = genesisBlock.id
    for (i <- 1 to length) {
      val block: PowBlock = randomPowBlockGenerator(fakeBestPowId, t0 + blockInterval * i * 100)
      bitcoinHistory.append(block) match {
        case Success((history, progressInfo)) =>
          bitcoinHistory = history
        case Failure(e) =>
          println(e)
      }
      fakeBestPowId = block.id
    }
    assert(bitcoinHistory.height == length + 1)
    assert(bitcoinHistory.bestPowId == recordBestPowId)
    length = 21
    fakeBestPowId = genesisBlock.id
    for (i <- 1 to length) {
      val block: PowBlock = randomPowBlockGenerator(fakeBestPowId, t0 + blockInterval * i * 100)
      bitcoinHistory.append(block) match {
        case Success((history, progressInfo)) =>
          bitcoinHistory = history
        case Failure(e) =>
          println(e)
      }
      fakeBestPowId = block.id
    }
    assert(bitcoinHistory.height == length + 1)
    assert(bitcoinHistory.bestPowId == fakeBestPowId)
  }
}
object HistoryTest {

}
