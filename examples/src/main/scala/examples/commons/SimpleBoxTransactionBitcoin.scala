package examples.commons

import com.google.common.primitives.{Bytes, Ints, Longs}
import examples.bitcoin.wallet.BitcoinBoxWallet
import io.circe.Encoder
import io.circe.syntax._
import io.iohk.iodb.ByteArrayWrapper
import scorex.core.serialization.Serializer
import scorex.core.transaction.BoxTransaction
import scorex.core.transaction.account.PublicKeyNoncedBox
import scorex.core.transaction.box.BoxUnlocker
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.proof.{Proof, Signature25519}
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion}
import scorex.core.utils.ScorexEncoding
import scorex.crypto.authds.ADKey
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.{Curve25519, PublicKey, Signature}

import scala.util.Try

//A transaction orders to destroy boxes associated with (pubkey -> nonce) and create new boxes (pubkey -> nonce)
// where a nonce is derived from a transaction and also a box index

// WARNING!: the scheme is not provably secure to replay attacks etc
case class SimpleBoxTransactionBitcoin(from: IndexedSeq[(PublicKey25519Proposition, Nonce)],
                                       to: IndexedSeq[(PublicKey25519Proposition, Value)],
                                       signatures: IndexedSeq[Signature25519],
                                       override val fee: Long,
                                       override val timestamp: Long) extends
  BoxTransaction[PublicKey25519Proposition, PublicKey25519NoncedBox] {

  override type M = SimpleBoxTransactionBitcoin

  lazy val boxIdsToOpen: IndexedSeq[ADKey] = from.map { case (prop, nonce) =>
    PublicKeyNoncedBox.idFromBox(prop, nonce)
  }

  override lazy val unlockers: Traversable[BoxUnlocker[PublicKey25519Proposition]] = boxIdsToOpen.zip(signatures).map {
    case (boxId, signature) =>
      new BoxUnlocker[PublicKey25519Proposition] {
        override val closedBoxId: ADKey = boxId
        override val boxKey: Proof[PublicKey25519Proposition] = signature
      }
  }

  lazy val hashNoNonces = Blake2b256(
    Bytes.concat(scorex.core.utils.concatFixLengthBytes(to.map(_._1.pubKeyBytes)),
      scorex.core.utils.concatFixLengthBytes(unlockers.map(_.closedBoxId)),
      Longs.toByteArray(timestamp),
      Longs.toByteArray(fee))
  )

  override lazy val newBoxes: Traversable[PublicKey25519NoncedBox] = to.zipWithIndex.map { case ((prop, value), idx) =>
    val nonce = SimpleBoxTransactionBitcoin.nonceFromDigest(Blake2b256(prop.pubKeyBytes ++ hashNoNonces ++ Ints.toByteArray(idx)))
    PublicKey25519NoncedBox(prop, nonce, value)
  }

  override lazy val serializer = SimpleBoxTransactionBitcoinCompanion

  override def toString: String = s"SimpleBoxTransactionBitcoin(${this.asJson.noSpaces})"

  lazy val semanticValidity: Try[Unit] = Try {
    require(from.size >0, s"Number of senders = ${from.size}")
    require(from.size == signatures.size)
    to.zipWithIndex.foreach{ case(tx, i) => require(tx._2>=0, s"Value of tx number ${i} is ${tx._2}." +
                                                              s" Total recipients ${to.length}") }
//    require(to.forall(_._2 >= 0), s"Total txs ${to.length}." +
//      s"Problem: ${to.zipWithIndex.forall{ case(a, i) => if (a._2<0) i.toString + ";" + a._2.toString} }")
    require(fee >= 0)
    require(timestamp >= 0)
    require(boxIdsToOpen.map(to => ByteArrayWrapper(to)).distinct.size == boxIdsToOpen.size)
    require(from.zip(signatures).forall { case ((prop, _), proof) =>
      proof.isValid(prop, messageToSign)
    })
  }
}


object SimpleBoxTransactionBitcoin extends ScorexEncoding {

  implicit val simpleBoxEncoder: Encoder[SimpleBoxTransactionBitcoin] = (sbe: SimpleBoxTransactionBitcoin) =>
    Map(
      "id" -> encoder.encodeId(sbe.id).asJson,
      "newBoxes" -> sbe.newBoxes.map(b => encoder.encode(b.id).asJson).toSeq.asJson,
      "boxesToRemove" -> sbe.boxIdsToOpen.map(id => encoder.encode(id).asJson).asJson,
      "from" -> sbe.from.map { s =>
        Map(
          "proposition" -> encoder.encode(s._1.pubKeyBytes).asJson,
          "nonce" -> s._2.toLong.asJson
        ).asJson
      }.asJson,
      "to" -> sbe.to.map { s =>
        Map(
          "proposition" -> encoder.encode(s._1.pubKeyBytes).asJson,
          "value" -> s._2.toLong.asJson
        ).asJson
      }.asJson,
      "signatures" -> sbe.signatures.map(s => encoder.encode(s.signature).asJson).asJson,
      "fee" -> sbe.fee.asJson,
      "timestamp" -> sbe.timestamp.asJson
    ).asJson

  def nonceFromDigest(digest: Array[Byte]): Nonce = Nonce @@ Longs.fromByteArray(digest.take(8))

  def apply(from: IndexedSeq[(PrivateKey25519, Nonce)],
            to: IndexedSeq[(PublicKey25519Proposition, Value)],
            fee: Long,
            timestamp: Long): SimpleBoxTransactionBitcoin = {
    val fromPub = from.map { case (pr, n) => pr.publicImage -> n }
    val fakeSigs = from.map(_ => Signature25519(Signature @@ Array[Byte]()))

    val undersigned = SimpleBoxTransactionBitcoin(fromPub, to, fakeSigs, fee, timestamp)

    val msg = undersigned.messageToSign
    val sigs = from.map { case (priv, _) => PrivateKey25519Companion.sign(priv, msg) }

    new SimpleBoxTransactionBitcoin(fromPub, to, sigs, fee, timestamp)
  }

  def create(w: BitcoinBoxWallet,
             to: Seq[(PublicKey25519Proposition, Value)],
             fee: Long,
             boxesIdsToExclude: Seq[Array[Byte]] = Seq()): Try[SimpleBoxTransactionBitcoin] = Try {
    var s = 0L
    val amount = to.map(_._2.toLong).sum

    val from: IndexedSeq[(PrivateKey25519, Nonce, Value)] = scala.util.Random.shuffle(
        w.boxes().filter(b => !boxesIdsToExclude.exists(id => java.util.Arrays.equals(id, b.box.id)))
    )
      .takeWhile { b =>
      s = s + b.box.value
      s < amount + b.box.value + fee //Gerui: add fee here to avoid shortage of tokens
    }.flatMap { b =>
      w.secretByPublicImage(b.box.proposition).map(s => (s, b.box.nonce, b.box.value))
    }.toIndexedSeq
    val canSend = from.map(_._3.toLong).sum

    // Sending the change back to a random key of the sender
    val senderPubKeys = if (w.publicKeys.isEmpty) w.generateNewSecret().publicKeys else w.publicKeys
    val randomPosition = scala.util.Random.nextInt(senderPubKeys.size)
    @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
    val changeTx: (PublicKey25519Proposition, Value) = (senderPubKeys.toVector(randomPosition), Value @@ (canSend - amount - fee))

    require(changeTx._2 >= 0)//Gerui: we don't need negative transaction to be created

    val outputs: IndexedSeq[(PublicKey25519Proposition, Value)] = (to :+ changeTx).toIndexedSeq

    require(from.map(_._3.toLong).sum - outputs.map(_._2.toLong).sum == fee)

    val timestamp = System.currentTimeMillis()
    SimpleBoxTransactionBitcoin(from.map(t => t._1 -> t._2), outputs, fee, timestamp)
  }
}


object SimpleBoxTransactionBitcoinCompanion extends Serializer[SimpleBoxTransactionBitcoin] {

  override def toBytes(m: SimpleBoxTransactionBitcoin): Array[Byte] = {
    Bytes.concat(Longs.toByteArray(m.fee),
      Longs.toByteArray(m.timestamp),
      Ints.toByteArray(m.signatures.length),
      Ints.toByteArray(m.from.length),
      Ints.toByteArray(m.to.length),
      m.signatures.foldLeft(Array[Byte]())((a, b) => Bytes.concat(a, b.bytes)),
      m.from.foldLeft(Array[Byte]())((a, b) => Bytes.concat(a, b._1.bytes, Longs.toByteArray(b._2))),
      m.to.foldLeft(Array[Byte]())((a, b) => Bytes.concat(a, b._1.bytes, Longs.toByteArray(b._2)))
    )
  }

  override def parseBytes(bytes: Array[Byte]): Try[SimpleBoxTransactionBitcoin] = Try {
    val fee = Longs.fromByteArray(bytes.slice(0, 8))
    val timestamp = Longs.fromByteArray(bytes.slice(8, 16))
    val sigLength = Ints.fromByteArray(bytes.slice(16, 20))
    val fromLength = Ints.fromByteArray(bytes.slice(20, 24))
    val toLength = Ints.fromByteArray(bytes.slice(24, 28))
    val signatures = (0 until sigLength) map { i =>
      Signature25519(Signature @@ bytes.slice(28 + i * Curve25519.SignatureLength, 28 + (i + 1) * Curve25519.SignatureLength))
    }
    val s = 28 + sigLength * Curve25519.SignatureLength
    val elementLength = 8 + Curve25519.KeyLength
    val from = (0 until fromLength) map { i =>
      val pk = PublicKey @@ bytes.slice(s + i * elementLength, s + (i + 1) * elementLength - 8)
      val v = Longs.fromByteArray(bytes.slice(s + (i + 1) * elementLength - 8, s + (i + 1) * elementLength))
      (PublicKey25519Proposition(pk), Nonce @@ v)
    }
    val s2 = s + fromLength * elementLength
    val to = (0 until toLength) map { i =>
      val pk = PublicKey @@ bytes.slice(s2 + i * elementLength, s2 + (i + 1) * elementLength - 8)
      val v = Longs.fromByteArray(bytes.slice(s2 + (i + 1) * elementLength - 8, s2 + (i + 1) * elementLength))
      (PublicKey25519Proposition(pk), Value @@ v)
    }
    SimpleBoxTransactionBitcoin(from, to, signatures, fee, timestamp)
  }
}
