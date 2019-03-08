package org.bitcoins.testkit.chain

import org.bitcoins.chain.models.{BlockHeaderDb, BlockHeaderDbHelper}
import org.bitcoins.core.crypto.DoubleSha256DigestBE
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.blockchain.BlockHeader

abstract class BlockHeaderHelper {

  /**
    * The previous block to this was [[header2]]
    * [[https://blockstream.info/block/0000000000000000002339403dedc19ae93f6f3912d364b42f568afa1ba7cfec height #566,093]]
    */
  val header1: BlockHeader = {
    val hex =
      "00000020b45e33a345ad08ad2902cdd4101632fcbec009694b0c2500000000000000000016c99a795d8e0105d86f361341c7858d223fac261718bd608052822c5b4ae3cfd782815c505b2e17a56bb90b"
    BlockHeader.fromHex(hex)
  }

  val header1Db: BlockHeaderDb = {
    BlockHeaderDbHelper.fromBlockHeader(566093, header1)
  }

  /**
    * The next block is [[header1]] after this block
    * 000000000000000000250c4b6909c0befc321610d4cd0229ad08ad45a3335eb4
    * [[https://blockstream.info/block/000000000000000000250c4b6909c0befc321610d4cd0229ad08ad45a3335eb4 #566,092]]
    */
  val header2: BlockHeader = {
    val hex =
      "00000020a82ff9c62e69a6cbed277b7f2a9ac9da3c7133a59a6305000000000000000000f6cd5708a6ba38d8501502b5b4e5b93627e8dcc9bd13991894c6e04ade262aa99582815c505b2e17479a751b"
    BlockHeader.fromHex(hex)
  }

  val header2Db: BlockHeaderDb = {
    BlockHeaderDbHelper.fromBlockHeader(566092, header2)
  }

  lazy val twoValidHeaders: Vector[BlockHeader] = {
    //https://blockstream.info/block/0000000000000000002339403dedc19ae93f6f3912d364b42f568afa1ba7cfec?expand
    val headers = Vector(header1, header2)
    headers
  }

  /** Gives us a block header that has a bad prev hash (can't connect to anything */
  lazy val badPrevHash: BlockHeader = {
    BlockHeaderHelper.withPrevhash(bh = header1,
                                   newPrevHash = DoubleSha256DigestBE.empty)
  }

  lazy val badNonce: BlockHeader = {
    BlockHeaderHelper.withNonce(bh = header1, newNonce = UInt32.zero)
  }

  lazy val badNBits: BlockHeader = {
    BlockHeaderHelper.withNBits(bh = header1, newNBits = UInt32.zero)
  }

  /** Modifies the given block header with the newPrevHash */
  def withPrevhash(
      bh: BlockHeader,
      newPrevHash: DoubleSha256DigestBE): BlockHeader = {
    BlockHeader(version = bh.version,
                previousBlockHash = newPrevHash.flip,
                merkleRootHash = bh.merkleRootHash,
                time = bh.time,
                nBits = bh.nBits,
                nonce = bh.nonce)
  }

  /** Modifies the given block header with the new nonce */
  def withNonce(bh: BlockHeader, newNonce: UInt32): BlockHeader = {
    BlockHeader(version = bh.version,
                previousBlockHash = bh.previousBlockHash,
                merkleRootHash = bh.merkleRootHash,
                time = bh.time,
                nBits = bh.nBits,
                nonce = newNonce)
  }

  /** Modifies the given block header with the new nBits */
  def withNBits(bh: BlockHeader, newNBits: UInt32): BlockHeader = {
    BlockHeader(version = bh.version,
                previousBlockHash = bh.previousBlockHash,
                merkleRootHash = bh.merkleRootHash,
                time = bh.time,
                nBits = newNBits,
                nonce = bh.nonce)
  }

}

object BlockHeaderHelper extends BlockHeaderHelper
