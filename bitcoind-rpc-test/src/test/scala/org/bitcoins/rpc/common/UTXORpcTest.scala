package org.bitcoins.rpc.common

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.rpc.{BitcoindRpcTestConfig, BitcoindRpcTestUtil}
import org.bitcoins.rpc.client.common.RpcOpts.AddNodeArgument
import org.bitcoins.rpc.client.common.{BitcoindRpcClient, RpcOpts}
import org.scalatest.{AsyncFlatSpec, BeforeAndAfterAll}

import scala.concurrent.{Await, ExecutionContext}

class UTXORpcTest extends AsyncFlatSpec with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem("UTXORpcTest")
  implicit val m: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext = m.executionContext
  implicit val networkParam: NetworkParameters = BitcoindRpcTestUtil.network

  implicit val client: BitcoindRpcClient = new BitcoindRpcClient(
    BitcoindRpcTestUtil.instance())
  val otherClient = new BitcoindRpcClient(BitcoindRpcTestUtil.instance())

  override def beforeAll(): Unit = {
    import BitcoindRpcTestConfig.DEFAULT_TIMEOUT

    val startF = BitcoindRpcTestUtil.startServers(Vector(client, otherClient))
    Await.result(startF, DEFAULT_TIMEOUT)

    val addNodeF =
      client.addNode(otherClient.getDaemon.uri, AddNodeArgument.Add)
    Await.result(addNodeF, DEFAULT_TIMEOUT)

    Await.result(client.generate(200), DEFAULT_TIMEOUT)
  }

  override protected def afterAll(): Unit = {
    BitcoindRpcTestUtil.stopServers(Vector(client, otherClient))
    TestKit.shutdownActorSystem(system)
  }

  behavior of "UTXORpc"

  it should "be able to list utxos" in {
    client.listUnspent.flatMap { unspent =>
      BitcoindRpcTestUtil.getFirstBlock.flatMap { block =>
        val address = block.tx.head.vout.head.scriptPubKey.addresses.get.head
        val unspentMined =
          unspent.filter(addr => addr.address.contains(address))
        assert(unspentMined.length >= 100)
      }
    }
  }

  it should "be able to lock and unlock utxos as well as list locked utxos" in {
    client.listUnspent.flatMap { unspent =>
      val txid1 = unspent(0).txid
      val vout1 = unspent(0).vout
      val txid2 = unspent(1).txid
      val vout2 = unspent(1).vout
      val param = Vector(RpcOpts.LockUnspentOutputParameter(txid1, vout1),
                         RpcOpts.LockUnspentOutputParameter(txid2, vout2))
      client.lockUnspent(unlock = false, param).flatMap { success =>
        assert(success)
        client.listLockUnspent.flatMap { locked =>
          assert(locked.length == 2)
          assert(locked(0).txId.flip == txid1)
          assert(locked(1).txId.flip == txid2)
          client.lockUnspent(unlock = true, param).flatMap { success =>
            assert(success)
            client.listLockUnspent.map { newLocked =>
              assert(newLocked.isEmpty)
            }
          }
        }
      }
    }
  }

  it should "be able to get utxo info" in {
    BitcoindRpcTestUtil.getFirstBlock.flatMap { block =>
      client.getTxOut(block.tx.head.txid, 0).map { info1 =>
        assert(info1.coinbase)
      }
    }
  }
}