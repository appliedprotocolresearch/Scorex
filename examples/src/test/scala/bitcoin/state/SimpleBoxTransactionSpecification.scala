package bitcoin.state

import examples.commons.{Nonce, PublicKey25519NoncedBox, SimpleBoxTransactionBitcoin, Value}
import examples.bitcoin.state.BitcoinBoxStoredState
import bitcoin.BitcoinGenerators
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.proof.Signature25519
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.hash.Sha256
import scorex.crypto.signatures.{PublicKey, Signature}
import scorex.util.encode.Base58

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
class SimpleBoxTransactionSpecification extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers
  with BitcoinGenerators {


  property("Transaction boxes are deterministic") {
    val GenesisAccountsNum = 10
    val GenesisBalance = Value @@ 100000L

    val icoMembers = (1 to 10) map (i => PublicKey25519Proposition(PublicKey @@ Sha256(i.toString)))
    icoMembers.map(_.address).mkString(",") shouldBe "016b86b273ff34fce19d6b804eff5a3f5747ada4eaa22f1d49c01e52ddb7875b4b658ee50f,01d4735e3a265e16eee03f59718b9b5d03019c07d8b6c51f90da3a666eec13ab35aca2afcd,014e07408562bedb8b60ce05c1decfe3ad16b72230967de01f640b7e4729b49fce65d47374,014b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8ae325532b,01ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39de3a3a62c,01e7f6c011776e8db7cd330b54174fd76f7d0216b612387a5ffcfb81e6f0919683f1c991e5,017902699be42c8a8e46fbbb4501726517e86b22c56a189f7625a6da49081b245190431363,012c624232cdd221771294dfbb310aca000a0df6ac8b66b696d90ef06fdefb64a3f41c07f5,0119581e27de7ced00ff1ce50b2047e7a567c76b1cbaebabe5ef03f7c3017bb5b7a7c7c153,014a44dc15364204a80fe80e9039455cc1608281820fe2b24f1e5233ade6af1dd5d5ab8000"

    val genesisAccount = PrivateKey25519Companion.generateKeys("genesis".getBytes)._1
    val tx = SimpleBoxTransactionBitcoin(IndexedSeq(genesisAccount -> Nonce @@ 0L), icoMembers.map(_ -> GenesisBalance), 0L, 0L)
    tx.newBoxes.toSeq shouldBe
      Vector(
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("3m6nhP4AZjFn5pgMd3PvH6PwHx23AG4tvpLCuu7Wt3hhAPssKc").get.take(33).tail), Nonce @@ -6219502975712200872L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("4ZJwiEzpTHhvT6BMYZg1FUXysHkuBLRHb7FvXhZGx6HtsWZCeG").get.take(33).tail), Nonce @@ 2326174055960855030L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("3Y7Ji8wrYZ12EPup6ky2mWEaNo1wTgUKVPJ84xaHwHqTC6LXoh").get.take(33).tail), Nonce @@ -2090466149357841238L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("3WqPcQ1w1HEaEDvHpnnqqYxJBzQGcf5gT5G5CrsXFL7UX4SA2N").get.take(33).tail), Nonce @@ -4786344880748433993L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("4m5cG82kztD9bZVf1Tc1Ni1uvHobpKYuAUyxNSnDm7WLGCZvZh").get.take(33).tail), Nonce @@ 2879476891976400353L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("4huPANjYcqcdRm99tsCw29JqFnHMTJZsQjoufRQTEDPPoWmPSt").get.take(33).tail), Nonce @@ 4610029492489107892L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("3s3CauhVba81UefEuuaNqRqGLEV9jCZJpvLFg5dJdu29TivRZk").get.take(33).tail), Nonce @@ 416797087985622128L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("3HHuHxBf2eXmbUcGuFCx3dU6Wp7imeRiN5uz4rYDdQwsLwnwW4").get.take(33).tail), Nonce @@ -8485818448745401936L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("38uZVfModMnCg5FSECtFiBE7Dbjmh7Tt1SgBD8gFTA1XDHxiqQ").get.take(33).tail), Nonce @@ -4750873086163930339L, Value @@ 100000L),
        PublicKey25519NoncedBox(PublicKey25519Proposition(PublicKey @@ Base58.decode("3WTH7tB28nkbC9KFJTy8EBn1bWkxryiLKDnngeP9BYyuCik3aP").get.take(33).tail), Nonce @@ 1904873933279744536L, Value @@ 100000L))
  }

  property("Generated transaction is valid") {
    forAll(simpleBoxTransactionBitcoinGen) { tx =>
      BitcoinBoxStoredState.semanticValidity(tx).isSuccess shouldBe true
    }
  }

  property("Transaction with modified signature is invalid") {
    forAll(simpleBoxTransactionBitcoinGen) { tx =>
      val wrongSig = Signature @@ ((tx.signatures.head.bytes.head + 1).toByte +: tx.signatures.head.bytes.tail)
      val wrongSigs = (Signature25519(wrongSig) +: tx.signatures.tail).toIndexedSeq
      BitcoinBoxStoredState.semanticValidity(tx.copy(signatures = wrongSigs)).isSuccess shouldBe false
    }
  }

  property("Transaction with modified from is invalid") {
    forAll(simpleBoxTransactionBitcoinGen) { tx =>
      val wrongFromPub = tx.from.map(p => (p._1, Nonce @@ (p._2 + 1)))
      BitcoinBoxStoredState.semanticValidity(tx.copy(from = wrongFromPub)).isSuccess shouldBe false
    }
  }

  property("Transaction with modified timestamp is invalid") {
    forAll(simpleBoxTransactionBitcoinGen) { tx =>
      BitcoinBoxStoredState.semanticValidity(tx.copy(timestamp = tx.timestamp + 1)).isSuccess shouldBe false
    }
  }

  property("Transaction with modified fee is invalid") {
    forAll(simpleBoxTransactionBitcoinGen) { tx =>
      BitcoinBoxStoredState.semanticValidity(tx.copy(fee = tx.fee + 1)).isSuccess shouldBe false
    }
  }

}
