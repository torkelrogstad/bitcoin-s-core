package org.bitcoins.rpc.v17
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.currency.Bitcoins
import org.bitcoins.core.number.UInt32
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.transaction.{
  TransactionConstants,
  TransactionInput,
  TransactionOutPoint
}
import org.bitcoins.rpc.BitcoindRpcTestUtil
import org.bitcoins.rpc.client.common.RpcOpts.AddNodeArgument
import org.bitcoins.rpc.client.v17.BitcoindV17RpcClient
import org.bitcoins.rpc.jsonmodels.{FinalizedPsbt, NonFinalizedPsbt}
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class PsbtRpcTest extends AsyncFlatSpec with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem(
    "BitcoindV17RpcClientTest_ActorSystem")
  implicit val m: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = m.executionContext
  implicit val networkParam: NetworkParameters = BitcoindRpcTestUtil.network

  private val clientAccum = Vector.newBuilder[BitcoindV17RpcClient]

  lazy val clientsF: Future[
    (BitcoindV17RpcClient, BitcoindV17RpcClient, BitcoindV17RpcClient)] = {
    val client = new BitcoindV17RpcClient(BitcoindRpcTestUtil.v17Instance())
    val otherClient = new BitcoindV17RpcClient(
      BitcoindRpcTestUtil.v17Instance())
    val thirdClient = new BitcoindV17RpcClient(
      BitcoindRpcTestUtil.v17Instance())

    clientAccum += (client, otherClient, thirdClient)

    val startF =
      BitcoindRpcTestUtil.startServers(Vector(client, otherClient, thirdClient))

    val pairsF = startF.map { _ =>
      List((client, otherClient), (client, thirdClient))
    }

    val addNodesF: Future[List[Unit]] = {
      pairsF.flatMap { pairs =>
        val addedF = pairs.map {
          case (first, second) =>
            first.addNode(second.getDaemon.uri, AddNodeArgument.Add)
        }
        Future.sequence(addedF)
      }
    }

    for {
      pairs <- pairsF
      _ <- addNodesF
      _ <- {
        val connectedPairsF = pairs.map {
          case (first, second) =>
            BitcoindRpcTestUtil.awaitConnectionF(first,
                                                 second,
                                                 duration = 1.second)
        }
        Future.sequence(connectedPairsF)
      }
      _ <- {
        BitcoindRpcTestUtil.generateAllAndSync(
          Vector(client, otherClient, thirdClient),
          blocks = 200)
      }
    } yield (client, otherClient, thirdClient)

  }

  override protected def afterAll(): Unit = {
    BitcoindRpcTestUtil.stopServers(clientAccum.result)
    TestKit.shutdownActorSystem(system)
  }

  behavior of "PsbtRpc"

  // https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki#Test_Vectors
  it should "decode all the BIP174 example PSBTs" in {
    val psbts = Vector(
      "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",
      "cHNidP8BAFUCAAAAASeaIyOl37UfxF8iD6WLD8E+HjNCeSqF1+Ns1jM7XLw5AAAAAAD/////AaBa6gsAAAAAGXapFP/pwAYQl8w7Y28ssEYPpPxCfStFiKwAAAAAAAEBIJVe6gsAAAAAF6kUY0UgD2jRieGtwN8cTRbqjxTA2+uHIgIDsTQcy6doO2r08SOM1ul+cWfVafrEfx5I1HVBhENVvUZGMEMCIAQktY7/qqaU4VWepck7v9SokGQiQFXN8HC2dxRpRC0HAh9cjrD+plFtYLisszrWTt5g6Hhb+zqpS5m9+GFR25qaAQEEIgAgdx/RitRZZm3Unz1WTj28QvTIR3TjYK2haBao7UiNVoEBBUdSIQOxNBzLp2g7avTxI4zW6X5xZ9Vp+sR/HkjUdUGEQ1W9RiED3lXR4drIBeP4pYwfv5uUwC89uq/hJ/78pJlfJvggg71SriIGA7E0HMunaDtq9PEjjNbpfnFn1Wn6xH8eSNR1QYRDVb1GELSmumcAAACAAAAAgAQAAIAiBgPeVdHh2sgF4/iljB+/m5TALz26r+En/vykmV8m+CCDvRC0prpnAAAAgAAAAIAFAACAAAA=",
      "cHNidP8BAD8CAAAAAf//////////////////////////////////////////AAAAAAD/////AQAAAAAAAAAAA2oBAAAAAAAACg8BAgMEBQYHCAkPAQIDBAUGBwgJCgsMDQ4PAAA=",
      "cHNidP8BACoCAAAAAAFAQg8AAAAAABepFG6Rty1Vk+fUOR4v9E6R6YXDFkHwhwAAAAAAAA==" // this one is from Core
    )

    for {
      (client, _, _) <- clientsF
      __ <- Future.sequence(psbts.map(client.decodePsbt))
    } yield succeed
  }

  it should "convert raw TXs to PSBTs and then decode them" in {
    for {
      (client, _, _) <- clientsF
      address <- client.getNewAddress
      // bcAddress <- client.getNewAddress(AddressType.Bech32) // todo(torkelrogstad) to test witness part of PSBTs
      // needs to happen after rebase
      rawTx <- client.createRawTransaction(Vector.empty,
                                           Map(address -> Bitcoins.one))
      fundedRawTx <- client.fundRawTransaction(rawTx)
      psbt <- client.convertToPsbt(fundedRawTx.hex)
      _ <- client.decodePsbt(psbt)
    } yield succeed

  }

  it should "finalize a noncompleted PSBT"

  it should "finalize a simple PSBT" in {
    for {
      (client, _, _) <- clientsF
      addr <- client.getNewAddress
      txid <- BitcoindRpcTestUtil.fundBlockChainTransaction(client,
                                                            addr,
                                                            Bitcoins.one)
      vout <- BitcoindRpcTestUtil.findOutput(client, txid, Bitcoins.one)
      newAddr <- client.getNewAddress
      psbt <- client.createPsbt(Vector(
                                  TransactionInput.fromTxidAndVout(
                                    txid.flip,
                                    vout)), // todo(torkelrogstad) BE txid
                                Map(newAddr -> Bitcoins(0.5)))
      processed <- client.walletProcessPsbt(psbt)
      finalized <- client.finalizePsbt(processed.psbt)
    } yield
      finalized match {
        case _: FinalizedPsbt    => succeed
        case _: NonFinalizedPsbt => fail
      }
  }

  // copies this test from Core: https://github.com/bitcoin/bitcoin/blob/master/test/functional/rpc_psbt.py#L158
  it should "combine PSBTs from multiple sources" in {
    for {
      (client, otherClient, thirdClient) <- clientsF
      // create outputs for transaction
      clientAddr <- client.getNewAddress
      otherClientAddr <- otherClient.getNewAddress
      clientTxid <- thirdClient.sendToAddress(clientAddr, Bitcoins.one)
      otherClientTxid <- thirdClient.sendToAddress(otherClientAddr,
                                                   Bitcoins.one)

      _ <- BitcoindRpcTestUtil.generateAndSync(
        Vector(thirdClient, client, otherClient))

      rawClientTx <- client.getRawTransaction(clientTxid)
      _ = assert(rawClientTx.confirmations.exists(_ > 0))

      clientVout <- BitcoindRpcTestUtil.findOutput(client,
                                                   clientTxid,
                                                   Bitcoins.one)
      otherClientVout <- BitcoindRpcTestUtil.findOutput(otherClient,
                                                        otherClientTxid,
                                                        Bitcoins.one)

      // create a psbt spending outputs generated above
      newAddr <- thirdClient.getNewAddress
      psbt <- {
        val inputs =
          Vector(
            TransactionInput
              .fromTxidAndVout(clientTxid.flip, clientVout), // todo(torkelrogstad) BE txid
            TransactionInput.fromTxidAndVout(otherClientTxid.flip,
                                             otherClientVout)
          ) // todo(torkelrogstad) BE txid

        thirdClient.createPsbt(inputs, Map(newAddr -> Bitcoins(1.5)))
      }
      // Update psbts, should only have data for one input and not the other
      clientProcessedPsbt <- client.walletProcessPsbt(psbt).map(_.psbt)

      otherClientProcessedPsbt <- otherClient
        .walletProcessPsbt(psbt)
        .map(_.psbt)

      // Combine and finalize the psbts
      combined <- thirdClient.combinePsbt(
        Vector(clientProcessedPsbt, otherClientProcessedPsbt))
      finalized <- thirdClient.finalizePsbt(combined)
    } yield {
      finalized match {
        case _: FinalizedPsbt    => succeed
        case _: NonFinalizedPsbt => fail
      }
    }
  }

  it should "create a PSBT and then decode it" in {
    for {
      (client, _, _) <- clientsF
      address <- client.getNewAddress
      input <- client.listUnspent.map(_.filter(_.spendable).head)
      psbt <- {
        val outpoint =
          TransactionOutPoint(input.txid.flip, UInt32(input.vout))
        val ourInput = TransactionInput(outpoint,
                                        ScriptSignature.empty,
                                        TransactionConstants.sequence)
        client.createPsbt(
          Vector(ourInput),
          Map(address -> Bitcoins(input.amount.toBigDecimal / 2)))
      }
      _ <- client.decodePsbt(psbt)
    } yield {
      succeed
    }

  }

  it should "create a funded wallet PSBT and then decode it" in {
    for {
      (client, _, _) <- clientsF
      address <- client.getNewAddress
      input <- client.listUnspent.map(_.filter(_.spendable).head)
      psbt <- {
        val outpoint = TransactionOutPoint(input.txid.flip, UInt32(input.vout))
        val ourInput = TransactionInput(outpoint,
                                        ScriptSignature.empty,
                                        TransactionConstants.sequence)
        client.walletCreateFundedPsbt(
          Vector(ourInput),
          Map(address -> Bitcoins(input.amount.toBigDecimal / 2)))
      }
      _ <- client.decodePsbt(psbt.psbt)
    } yield {
      succeed
    }
  }

}