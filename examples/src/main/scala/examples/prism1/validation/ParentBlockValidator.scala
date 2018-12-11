package examples.prism1.validation

import examples.prism1.blocks.{HybridBlock, PosBlock, PowBlock}
import examples.prism1.history.HistoryStorage
import scorex.core.block.BlockValidator
import scorex.core.utils.ScorexEncoding

import scala.util.Try

class ParentBlockValidator(storage: HistoryStorage)
  extends BlockValidator[HybridBlock] with ScorexEncoding {

  def validate(block: HybridBlock): Try[Unit] = Try {
    block match {
      case powBlock: PowBlock => if (!storage.isGenesis(powBlock)) {
        //check PoW parent id ???
        require(storage.modifierById(powBlock.parentId).isDefined, s"Parent ${encoder.encodeId(powBlock.parentId)} missed")
        //check referenced PoS block exists as well
        // TODO: review me - .get
        @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
        val posBlock = storage.modifierById(powBlock.prevPosId).get

        //check referenced PoS block points to parent PoW block
        require(posBlock.parentId == posBlock.parentId, "ref rule broken")
      }
      case posBlock: PosBlock =>
        //check PoW block exists
        require(storage.modifierById(posBlock.parentId).isDefined)
    }
  }

}
