package examples.bitcoin.wallet

import java.io.File

import com.google.common.primitives.Ints
import examples.commons._
import examples.bitcoin.blocks.BitcoinBlock
import examples.bitcoin.mining.WalletSettings
import examples.bitcoin.state.BitcoinBoxStoredState
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core._
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion, PrivateKey25519Serializer}
import scorex.core.transaction.wallet.{BoxWallet, BoxWalletTransaction, WalletBox, WalletBoxSerializer}
import scorex.core.utils.ScorexEncoding
import scorex.util.encode.Base58
import scorex.crypto.hash.Blake2b256
import scorex.util.ScorexLogging

import scala.util.Try


case class BitcoinBoxWallet(seed: Array[Byte], store: LSMStore)
  extends BoxWallet[PublicKey25519Proposition, SimpleBoxTransactionBitcoin, BitcoinBlock, BitcoinBoxWallet]
    with ScorexLogging with ScorexEncoding {

  override type S = PrivateKey25519
  override type PI = PublicKey25519Proposition

  private val SecretsKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(store.keySize)(2: Byte))

  private val BoxIdsKey: ByteArrayWrapper = ByteArrayWrapper(Array.fill(store.keySize)(1: Byte))

  def boxIds: Seq[Array[Byte]] = {
    store.get(BoxIdsKey).map(_.data.grouped(store.keySize).toSeq).getOrElse(Seq[Array[Byte]]())
  }

  private lazy val walletBoxSerializer =
    new WalletBoxSerializer[PublicKey25519Proposition, PublicKey25519NoncedBox](PublicKey25519NoncedBoxSerializer)

  //intentionally not implemented for now
  override def historyTransactions: Seq[BoxWalletTransaction[PublicKey25519Proposition, SimpleBoxTransactionBitcoin]] = ???

  override def boxes(): Seq[WalletBox[PublicKey25519Proposition, PublicKey25519NoncedBox]] = {
    boxIds
      .flatMap(id => store.get(ByteArrayWrapper(id)))
      .map(_.data)
      .map(ba => walletBoxSerializer.parseBytes(ba))
      .map(_.get)
      .filter(_.box.value > 0)
  }

  override def publicKeys: Set[PublicKey25519Proposition] = secrets.map(_.publicImage)

  override def secrets: Set[PrivateKey25519] = store.get(SecretsKey)
    .map(_.data.grouped(64).map(b => PrivateKey25519Serializer.parseBytes(b).get).toSet)
    .getOrElse(Set.empty[PrivateKey25519])

  override def secretByPublicImage(publicImage: PublicKey25519Proposition): Option[PrivateKey25519] =
    secrets.find(s => s.publicImage == publicImage)

  override def generateNewSecret(): BitcoinBoxWallet = {
    val prevSecrets = secrets
    val nonce: Array[Byte] = Ints.toByteArray(prevSecrets.size)
    val s = Blake2b256(seed ++ nonce)
    val (priv, _) = PrivateKey25519Companion.generateKeys(s)
    val allSecrets: Set[PrivateKey25519] = Set(priv) ++ prevSecrets
    store.update(ByteArrayWrapper(priv.privKeyBytes),
      Seq(),
      Seq(SecretsKey -> ByteArrayWrapper(allSecrets.toArray.flatMap(p => PrivateKey25519Serializer.toBytes(p)))))
    BitcoinBoxWallet(seed, store)
  }

  //we do not process offchain (e.g. by adding them to the wallet)
  override def scanOffchain(tx: SimpleBoxTransactionBitcoin): BitcoinBoxWallet = this

  override def scanOffchain(txs: Seq[SimpleBoxTransactionBitcoin]): BitcoinBoxWallet = this

  override def scanPersistent(modifier: BitcoinBlock): BitcoinBoxWallet = {
    log.debug(s"Applying modifier to wallet: ${encoder.encodeId(modifier.id)}")
    val changes = BitcoinBoxStoredState.changes(modifier).get

    val newBoxes = changes.toAppend.filter(s => secretByPublicImage(s.box.proposition).isDefined).map(_.box).map { box =>
      val boxTransaction = modifier.transactions.find(t => t.newBoxes.exists(tb => java.util.Arrays.equals(tb.id, box.id)))
      val txId = boxTransaction.map(_.id).getOrElse(bytesToId(Array.fill(32)(0: Byte)))
      val ts = boxTransaction.map(_.timestamp).getOrElse(modifier.timestamp)
      val wb = WalletBox[PublicKey25519Proposition, PublicKey25519NoncedBox](box, txId, ts)(PublicKey25519NoncedBoxSerializer)
      ByteArrayWrapper(box.id) -> ByteArrayWrapper(wb.bytes)
    }

    val boxIdsToRemove = changes.toRemove.view.map(_.boxId).map(ByteArrayWrapper.apply)
    val newBoxIds: ByteArrayWrapper = boxIds.filter(bi => !boxIdsToRemove.exists(w => java.util.Arrays.equals(w.data, bi))).flatten)
                                      ++ ByteArrayWrapper(newBoxes.toArray.flatMap(_._1.data)
    store.update(idToBAW(modifier.id), boxIdsToRemove, Seq(BoxIdsKey -> newBoxIds) ++ newBoxes)
    log.debug(s"Successfully applied modifier to wallet: ${encoder.encodeId(modifier.id)}")

    BitcoinBoxWallet(seed, store)
  }

  override def rollback(to: VersionTag): Try[BitcoinBoxWallet] = Try {
    if (store.lastVersionID.exists(w => bytesToVersion(w.data) == to)) {
      this
    } else {
      log.debug(s"Rolling back wallet to: ${encoder.encodeVersion(to)}")
      store.rollback(versionToBAW(to))
      log.debug(s"Successfully rolled back wallet to: ${encoder.encodeVersion(to)}")
      BitcoinBoxWallet(seed, store)
    }
  }

  override type NVCT = this.type

}

object BitcoinBoxWallet {

  def walletFile(settings: WalletSettings): File = {
    settings.walletDir.mkdirs()

    new File(s"${settings.walletDir.getAbsolutePath}/wallet.dat")
  }

  def exists(settings: WalletSettings): Boolean = walletFile(settings).exists()

  def readOrGenerate(settings: WalletSettings): BitcoinBoxWallet = {
    val seed = settings.seed.getBytes("UTF-8")
    val wFile = walletFile(settings)
    wFile.mkdirs()
    val boxesStorage = new LSMStore(wFile, maxJournalEntryCount = 10000, keepVersions = 100) //todo: configurable kV

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run(): Unit = {
        boxesStorage.close()
      }
    })

    BitcoinBoxWallet(seed, boxesStorage)
  }

  def readOrGenerate(settings: WalletSettings, accounts: Int): BitcoinBoxWallet =
    (1 to accounts).foldLeft(readOrGenerate(settings)) { case (w, _) =>
      w.generateNewSecret()
    }

  //wallet with applied initialBlocks
  def genesisWallet(settings: WalletSettings, initialBlocks: Seq[BitcoinBlock]): BitcoinBoxWallet = {
    initialBlocks.foldLeft(readOrGenerate(settings).generateNewSecret()) { (a, b) =>
      a.scanPersistent(b)
    }
  }
}


object GenesisStateGenerator extends App with ScorexEncoding {
  private val w1 = BitcoinBoxWallet(Base58.decode("minerNode1").get, new LSMStore(new File("/tmp/w1")))
  private val w2 = BitcoinBoxWallet(Base58.decode("minerNode2").get, new LSMStore(new File("/tmp/w2")))
  private val w3 = BitcoinBoxWallet(Base58.decode("minerNode3").get, new LSMStore(new File("/tmp/w3")))

  (1 to 20).foreach(_ => w1.generateNewSecret())
  (1 to 20).foreach(_ => w2.generateNewSecret())
  (1 to 10).foreach(_ => w3.generateNewSecret())

  val pks =
    w1.publicKeys.map(_.pubKeyBytes).map(encoder.encode) ++
      w2.publicKeys.map(_.pubKeyBytes).map(encoder.encode) ++
      w3.publicKeys.map(_.pubKeyBytes).map(encoder.encode)

  println(pks.map(pk => s""""$pk",""").mkString("\n"))
}
