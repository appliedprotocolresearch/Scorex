package bitcoin

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import commons.ExamplesCommonGenerators
import examples.commons.SimpleBoxTransactionBitcoinMemPool
import examples.bitcoin.BitcoinApp
import examples.bitcoin.history.BitcoinSyncInfoMessageSpec
import io.iohk.iodb.ByteArrayWrapper
import scorex.core._
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, ChangedMempool}
import scorex.core.network._
import scorex.core.utils.NetworkTimeProvider
import scorex.testkit.generators.CoreGenerators

import scala.concurrent.ExecutionContext.Implicits.global

@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
trait NodeViewSynchronizerGenerators {
  this: ModifierGenerators with StateGenerators with HistoryGenerators with HybridTypes with CoreGenerators with ExamplesCommonGenerators =>

  object NodeViewSynchronizerForTests {
    def props(networkControllerRef: ActorRef,
              viewHolderRef: ActorRef): Props =
      NodeViewSynchronizerRef.props[TX, HSI, SIS, PM, HT, MP](networkControllerRef,
        viewHolderRef,
        BitcoinSyncInfoMessageSpec,
        settings.scorexSettings.network,
        new NetworkTimeProvider(settings.scorexSettings.ntp),
        BitcoinApp.modifierSerializers)
  }

  /**
    * Generate a nodeViewSynchronizer for test and others for test
    * @param system
    * @return
    */
  def nodeViewSynchronizer(implicit system: ActorSystem):
  (ActorRef, HSI, PM, TX, ConnectedPeer, TestProbe, TestProbe, TestProbe, TestProbe) = {
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val h = historyGen.sample.get
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val sRaw = stateGen.sample.get
    val mempool = SimpleBoxTransactionBitcoinMemPool.emptyPool
    val v = h.openSurfaceIds().last
    sRaw.store.update(ByteArrayWrapper(idToBytes(v)), Seq(), Seq())
    val s = sRaw.copy(version = idToVersion(v))

    val ncProbe = TestProbe("NetworkControllerProbe")
    val vhProbe = TestProbe("ViewHolderProbe")
    val pchProbe = TestProbe("PeerHandlerProbe")
    val eventListener = TestProbe("EventListener")

    val ref = system.actorOf(NodeViewSynchronizerForTests.props(ncProbe.ref, vhProbe.ref))
    ref ! ChangedHistory(h)
    ref ! ChangedMempool(mempool)
    val m = totallyValidModifier(h, s)
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val tx = simpleBoxTransactionBitcoinGen.sample.get
    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    val p: ConnectedPeer = connectedPeerGen(pchProbe.ref).sample.get

    (ref, h.syncInfo, m, tx, p, pchProbe, ncProbe, vhProbe, eventListener)
  }
}