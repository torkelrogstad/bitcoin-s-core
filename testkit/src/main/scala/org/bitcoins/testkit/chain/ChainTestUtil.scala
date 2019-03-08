package org.bitcoins.testkit.chain

import org.bitcoins.chain.models.{BlockHeaderDb, BlockHeaderDbHelper}
import org.bitcoins.core.config.MainNet
import org.bitcoins.core.protocol.blockchain.{
  BlockHeader,
  MainNetChainParams,
  RegTestNetChainParams
}

sealed abstract class ChainTestUtil {
  lazy val regTestChainParams: RegTestNetChainParams.type =
    RegTestNetChainParams
  lazy val regTestHeader: BlockHeader =
    regTestChainParams.genesisBlock.blockHeader
  lazy val regTestHeaderDb: BlockHeaderDb = {
    BlockHeaderDbHelper.fromBlockHeader(height = 0, bh = regTestHeader)
  }

  lazy val mainnetChainParam: MainNetChainParams.type = MainNetChainParams
}

object ChainTestUtil extends ChainTestUtil
