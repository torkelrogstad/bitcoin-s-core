package org.bitcoins.rpc.common

import java.io.File
import java.util.Scanner

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.crypto.{ECPrivateKey, ECPublicKey}
import org.bitcoins.core.currency.{Bitcoins, Satoshis}
import org.bitcoins.core.number.{Int64, UInt32}
import org.bitcoins.core.protocol.P2PKHAddress
import org.bitcoins.core.protocol.script.ScriptSignature
import org.bitcoins.core.protocol.transaction.{
  TransactionInput,
  TransactionOutPoint
}
import org.bitcoins.core.wallet.fee.SatoshisPerByte
import org.bitcoins.rpc.client.common.RpcOpts.AddressType
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, RpcOpts}
import org.bitcoins.rpc.jsonmodels.RpcAddress
import org.bitcoins.rpc.{BitcoindRpcTestUtil, RpcUtil}
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll}

import scala.async.Async.{async, await}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class WalletRpcTest extends AsyncFlatSpec with BeforeAndAfterAll {
  implicit val system: ActorSystem =
    ActorSystem("WalletRpcTest", BitcoindRpcTestUtil.AKKA_CONFIG)
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val networkParam: NetworkParameters = BitcoindRpcTestUtil.network

  private val accum: mutable.Builder[
    BitcoindRpcClient,
    Vector[BitcoindRpcClient]] = Vector.newBuilder[BitcoindRpcClient]

  lazy val clientsF: Future[
    (BitcoindRpcClient, BitcoindRpcClient, BitcoindRpcClient)] =
    BitcoindRpcTestUtil.createNodeTriple(clientAccum = accum)

  // This client's wallet is encrypted
  lazy val walletClientF: Future[BitcoindRpcClient] = clientsF.flatMap { _ =>
    val walletClient = new BitcoindRpcClient(BitcoindRpcTestUtil.instance())
    accum += walletClient

    for {
      _ <- walletClient.start()
      _ <- walletClient.generate(200)
      _ <- walletClient.encryptWallet(password)
      _ <- walletClient.stop()
      _ <- RpcUtil.awaitServerShutdown(walletClient)
      _ <- walletClient.start()
    } yield walletClient
  }

  var password = "password"

  override protected def afterAll(): Unit = {
    BitcoindRpcTestUtil.stopServers(accum.result)
    TestKit.shutdownActorSystem(system)
  }

  behavior of "WalletRpc"

  it should "be able to dump the wallet" in {
    for {
      (client, _, _) <- clientsF
      result <- {
        val datadir = client.getDaemon.authCredentials.datadir
        client.dumpWallet(datadir + "/test.dat")
      }
    } yield {
      assert(result.filename.exists)
      assert(result.filename.isFile)
    }
  }

  it should "be able to list wallets" in {
    for {
      (client, _, _) <- clientsF
      wallets <- client.listWallets
    } yield assert(wallets == Vector("wallet.dat"))
  }

  it should "be able to backup the wallet" in {
    for {
      (client, _, _) <- clientsF
      _ <- {
        val datadir = client.getDaemon.authCredentials.datadir
        client.backupWallet(datadir + "/backup.dat")
      }
    } yield {
      val datadir = client.getDaemon.authCredentials.datadir
      val file = new File(datadir + "/backup.dat")
      assert(file.exists)
      assert(file.isFile)
    }
  }

  it should "be able to lock and unlock the wallet" in {
    for {
      walletClient <- walletClientF
      _ <- walletClient.walletLock()
      _ <- walletClient.walletPassphrase(password, 1000)
      info <- walletClient.getWalletInfo
      _ = {
        assert(info.unlocked_until.nonEmpty)
        assert(info.unlocked_until.get > 0)
      }
      _ <- walletClient.walletLock()

      newInfo <- walletClient.getWalletInfo
    } yield assert(newInfo.unlocked_until.contains(0))
  }

  it should "be able to get an address from bitcoind" in {
    for {
      (client, _, _) <- clientsF
      _ <- {
        val addrFuts =
          List(client.getNewAddress,
               client.getNewAddress(AddressType.Bech32),
               client.getNewAddress(AddressType.P2SHSegwit),
               client.getNewAddress(AddressType.Legacy))
        Future.sequence(addrFuts)
      }
    } yield succeed
  }

  it should "be able to get a new raw change address" in {
    for {
      (client, _, _) <- clientsF
      _ <- {
        val addrFuts =
          List(
            client.getRawChangeAddress,
            client.getRawChangeAddress(AddressType.Legacy),
            client.getRawChangeAddress(AddressType.Bech32),
            client.getRawChangeAddress(AddressType.P2SHSegwit)
          )
        Future.sequence(addrFuts)
      }
    } yield succeed
  }

  it should "be able to get the amount recieved by some address" in {
    for {
      (client, _, _) <- clientsF
      address <- client.getNewAddress
      amount <- client.getReceivedByAddress(address)
    } yield assert(amount == Bitcoins(0))
  }

  it should "be able to get the unconfirmed balance" in {
    for {
      (client, _, _) <- clientsF
      balance <- client.getUnconfirmedBalance
      transaction <- BitcoindRpcTestUtil.sendCoinbaseTransaction(client, client)
      newBalance <- client.getUnconfirmedBalance
    } yield {
      assert(balance == Bitcoins(0))
      assert(newBalance == transaction.amount)
    }
  }

  it should "be able to get the wallet info" in {
    for {
      (client, _, _) <- clientsF
      info <- client.getWalletInfo
    } yield {
      assert(info.balance.toBigDecimal > 0)
      assert(info.txcount > 0)
      assert(info.keypoolsize > 0)
      assert(!info.unlocked_until.contains(0))
    }
  }

  it should "be able to refill the keypool" in {
    for {
      (client, _, _) <- clientsF
      info <- client.getWalletInfo
      _ <- client.keyPoolRefill(info.keypoolsize + 1)
      newInfo <- client.getWalletInfo
    } yield assert(newInfo.keypoolsize == info.keypoolsize + 1)
  }

  it should "be able to change the wallet password" in {
    val newPass = "new_password"

    for {
      walletClient <- walletClientF
      _ <- walletClient.walletLock()
      _ <- walletClient.walletPassphraseChange(password, newPass)
      _ = {
        password = newPass
      }
      _ <- walletClient.walletPassphrase(password, 1000)
      info <- walletClient.getWalletInfo
      _ <- walletClient.walletLock()
      newInfo <- walletClient.getWalletInfo
    } yield {

      assert(info.unlocked_until.nonEmpty)
      assert(info.unlocked_until.get > 0)
      assert(newInfo.unlocked_until.contains(0))
    }
  }

  it should "be able to import funds without rescan and then remove them" in async {
    val (client, otherClient, thirdClient) = await(clientsF)

    val address = await(thirdClient.getNewAddress)
    val privKey = await(thirdClient.dumpPrivKey(address))

    val txidF =
      BitcoindRpcTestUtil
        .fundBlockChainTransaction(client, address, Bitcoins(1.5))
    val txid = await(txidF)

    await(client.generate(1))

    val tx = await(client.getTransaction(txid))

    val proof = await(client.getTxOutProof(Vector(txid)))

    val balanceBefore = await(otherClient.getBalance)

    await(otherClient.importPrivKey(privKey, rescan = false))
    await(otherClient.importPrunedFunds(tx.hex, proof))

    val balanceAfter = await(otherClient.getBalance)
    assert(balanceAfter == balanceBefore + Bitcoins(1.5))

    val addressInfo = await(otherClient.validateAddress(address))
    assert(addressInfo.ismine.contains(true))

    await(otherClient.removePrunedFunds(txid))

    val balance = await(otherClient.getBalance)
    assert(balance == balanceBefore)
  }

  it should "be able to list address groupings" in {
    for {
      (client, _, _) <- clientsF
      address <- client.getNewAddress

      _ <- BitcoindRpcTestUtil
        .fundBlockChainTransaction(client, address, Bitcoins(1.25))
      groupings <- client.listAddressGroupings
      block <- BitcoindRpcTestUtil.getFirstBlock(client)
    } yield {
      val rpcAddress =
        groupings.find(vec => vec.head.address == address).get.head
      assert(rpcAddress.address == address)
      assert(rpcAddress.balance == Bitcoins(1.25))

      val firstAddress =
        block.tx.head.vout.head.scriptPubKey.addresses.get.head

      val maxGroup =
        groupings
          .max(Ordering.by[Vector[RpcAddress], BigDecimal](addr =>
            addr.head.balance.toBigDecimal))
          .head

      assert(maxGroup.address == firstAddress)
    }
  }

  it should "be able to send to an address" in {
    for {
      (client, otherClient, _) <- clientsF
      address <- otherClient.getNewAddress
      txid <- client.sendToAddress(address, Bitcoins(1))
      transaction <- client.getTransaction(txid)
    } yield {
      assert(transaction.amount == Bitcoins(-1))
      assert(transaction.details.head.address.contains(address))
    }
  }

  it should "be able to send btc to many addresses" in {
    for {
      (client, otherClient, _) <- clientsF
      address1 <- otherClient.getNewAddress
      address2 <- otherClient.getNewAddress
      txid <- client
        .sendMany(Map(address1 -> Bitcoins(1), address2 -> Bitcoins(2)))
      transaction <- client.getTransaction(txid)
    } yield {
      assert(transaction.amount == Bitcoins(-3))
      assert(transaction.details(0).address.contains(address1))
      assert(transaction.details(1).address.contains(address2))
    }
  }

  it should "be able to list transactions by receiving addresses" in {
    for {
      (client, otherClient, _) <- clientsF
      address <- otherClient.getNewAddress
      txid <- BitcoindRpcTestUtil
        .fundBlockChainTransaction(client, address, Bitcoins(1.5))
      receivedList <- otherClient.listReceivedByAddress()
    } yield {
      val entryList =
        receivedList.filter(entry => entry.address == address)
      assert(entryList.length == 1)
      val entry = entryList.head
      assert(entry.txids.head == txid)
      assert(entry.address == address)
      assert(entry.amount == Bitcoins(1.5))
      assert(entry.confirmations == 1)
    }
  }

  it should "be able to import an address" in {
    for {
      (client, otherClient, _) <- clientsF
      address <- client.getNewAddress
      _ <- otherClient.importAddress(address)
      txid <- BitcoindRpcTestUtil.fundBlockChainTransaction(client,
                                                            address,
                                                            Bitcoins(1.5))
      list <- otherClient.listReceivedByAddress(includeWatchOnly = true)
    } yield {
      val entry =
        list
          .find(addr => addr.involvesWatchonly.contains(true))
          .get
      assert(entry.address == address)
      assert(entry.involvesWatchonly.contains(true))
      assert(entry.amount == Bitcoins(1.5))
      assert(entry.txids.head == txid)
    }
  }

  it should "be able to get the balance" in {
    for {
      (client, _, _) <- clientsF
      balance <- client.getBalance
      _ <- client.generate(1)
      newBalance <- client.getBalance
    } yield {
      assert(balance.toBigDecimal > 0)
      assert(balance.toBigDecimal < newBalance.toBigDecimal)
    }
  }

  it should "be able to dump a private key" in {
    for {
      (client, _, _) <- clientsF
      address <- client.getNewAddress
      _ <- client.dumpPrivKey(address)
    } yield succeed
  }

  it should "be able to import a private key" in {
    val ecPrivateKey = ECPrivateKey.freshPrivateKey
    val publicKey = ecPrivateKey.publicKey
    val address = P2PKHAddress(publicKey, networkParam)

    for {
      (client, _, _) <- clientsF
      _ <- client.importPrivKey(ecPrivateKey, rescan = false)
      key <- client.dumpPrivKey(address)
      result <- client
        .dumpWallet(
          client.getDaemon.authCredentials.datadir + "/wallet_dump.dat")
    } yield {
      assert(key == ecPrivateKey)
      val reader = new Scanner(result.filename)
      var found = false
      while (reader.hasNext) {
        if (reader.next == ecPrivateKey.toWIF(networkParam)) {
          found = true
        }
      }
      assert(found)
    }
  }

  it should "be able to import a public key" in {
    val pubKey = ECPublicKey.freshPublicKey
    for {
      (client, _, _) <- clientsF
      _ <- client.importPubKey(pubKey)
    } yield succeed
  }

  it should "be able to import multiple addresses with importMulti" in {
    val privKey = ECPrivateKey.freshPrivateKey
    val address1 = P2PKHAddress(privKey.publicKey, networkParam)

    val privKey1 = ECPrivateKey.freshPrivateKey
    val privKey2 = ECPrivateKey.freshPrivateKey

    for {
      (client, _, _) <- clientsF
      firstResult <- client
        .createMultiSig(2, Vector(privKey1.publicKey, privKey2.publicKey))
      address2 = firstResult.address

      secondResult <- client
        .importMulti(
          Vector(
            RpcOpts.ImportMultiRequest(RpcOpts.ImportMultiAddress(address1),
                                       UInt32(0)),
            RpcOpts.ImportMultiRequest(RpcOpts.ImportMultiAddress(address2),
                                       UInt32(0))),
          rescan = false
        )
    } yield {
      assert(secondResult.length == 2)
      assert(secondResult(0).success)
      assert(secondResult(1).success)
    }
  }

  it should "be able to import a wallet" in {
    for {
      (client, _, _) <- clientsF
      walletClient <- walletClientF
      address <- client.getNewAddress
      walletFile = client.getDaemon.authCredentials.datadir + "/client_wallet.dat"

      fileResult <- client.dumpWallet(walletFile)
      _ <- walletClient.walletPassphrase(password, 1000)
      _ <- walletClient.importWallet(walletFile)
      _ <- walletClient.dumpPrivKey(address)
    } yield assert(fileResult.filename.exists)

  }

  it should "be able to set the tx fee" in {
    for {
      (client, _, _) <- clientsF
      success <- client.setTxFee(Bitcoins(0.01))
      info <- client.getWalletInfo
    } yield {
      assert(success)
      assert(info.paytxfee == SatoshisPerByte(Satoshis(Int64(1000))))
    }
  }

  it should "be able to bump a mem pool tx fee" in {
    for {
      (client, otherClient, _) <- clientsF
      address <- otherClient.getNewAddress
      unspent <- client.listUnspent
      changeAddress <- client.getRawChangeAddress
      rawTx <- {
        val output =
          unspent.find(output => output.amount.toBigDecimal > 1).get
        val input =
          TransactionInput(
            TransactionOutPoint(output.txid.flip, UInt32(output.vout)),
            ScriptSignature.empty,
            UInt32.max - UInt32(2))
        val inputs = Vector(input)

        val outputs =
          Map(address -> Bitcoins(0.5),
              changeAddress -> Bitcoins(output.amount.toBigDecimal - 0.55))

        client.createRawTransaction(inputs, outputs)
      }
      stx <- BitcoindRpcTestUtil.signRawTransaction(client, rawTx)
      txid <- client.sendRawTransaction(stx.hex, allowHighFees = true)
      tx <- client.getTransaction(txid)
      bumpedTx <- client.bumpFee(txid)
    } yield assert(tx.fee.get < bumpedTx.fee)
  }

}
