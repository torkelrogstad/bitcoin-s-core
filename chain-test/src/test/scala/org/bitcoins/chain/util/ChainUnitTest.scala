package org.bitcoins.chain.util

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import org.bitcoins.chain.api.ChainApi
import org.bitcoins.chain.blockchain.{Blockchain, ChainHandler}
import org.bitcoins.chain.db.ChainDbManagement
import org.bitcoins.chain.models.{
  BlockHeaderDAO,
  BlockHeaderDb,
  BlockHeaderDbHelper
}
import org.bitcoins.core.protocol.blockchain.{
  BlockHeader,
  Block,
  ChainParams,
  RegTestNetChainParams
}
import org.bitcoins.core.util.BitcoinSLogger
import org.bitcoins.db.{DbConfig, UnitTestDbConfig}
import org.bitcoins.rpc.client.common.BitcoindRpcClient
import org.bitcoins.testkit.chain.ChainTestUtil
import org.bitcoins.testkit.rpc.BitcoindRpcTestUtil
import org.bitcoins.zmq.ZMQSubscriber
import org.scalatest._
import play.api.libs.json.{JsError, JsSuccess, Json}
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

trait ChainUnitTest
    extends fixture.AsyncFlatSpec
    with MustMatchers
    with BitcoinSLogger
    with BeforeAndAfter
    with BeforeAndAfterAll {

  implicit def system: ActorSystem

  val timeout = 10.seconds
  def dbConfig: DbConfig = UnitTestDbConfig

  val genesisHeaderDb: BlockHeaderDb = ChainTestUtil.regTestGenesisHeaderDb
  val chainParam: ChainParams = RegTestNetChainParams

  lazy val blockHeaderDAO = BlockHeaderDAO(chainParams =
                                             ChainTestUtil.regTestChainParams,
                                           dbConfig = dbConfig)

  lazy val blockchain =
    Blockchain.fromHeaders(Vector(genesisHeaderDb), blockHeaderDAO)

  lazy val chainHandler: ChainHandler = ChainHandler(blockchain)
  lazy val chainApi: ChainApi = chainHandler

  implicit def ec: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  def makeFixture[T](build: () => Future[T], destroy: () => Future[Any])(
      test: OneArgAsyncTest): FutureOutcome = {
    val outcomeF = build().flatMap { fixture =>
      test(fixture.asInstanceOf[FixtureParam]).toFuture
    }

    val destroyP = Promise[Unit]()
    outcomeF.onComplete { _ =>
      destroy().onComplete {
        case Success(_)   => destroyP.success(())
        case Failure(err) => destroyP.failure(err)
      }
    }

    val outcomeAfterDestroyF = destroyP.future.flatMap(_ => outcomeF)

    new FutureOutcome(outcomeAfterDestroyF)
  }

  def composeBuilders[T, U](
      builder: () => Future[T],
      dependentBuilder: T => Future[U]): () => Future[(T, U)] = () => {
    builder().flatMap { first =>
      dependentBuilder(first).map { second =>
        (first, second)
      }
    }
  }

  def composeBuildersAndWrap[T, U, C](
      builder: () => Future[T],
      dependentBuilder: T => Future[U],
      wrap: (T, U) => C): () => Future[C] = () => {
    composeBuilders(builder, dependentBuilder)().map {
      case (first, second) => wrap(first, second)
    }
  }

  def createBlockHeaderDAO(): Future[BlockHeaderDAO] = {
    val genesisHeaderF = setupHeaderTableWithGenesisHeader()

    genesisHeaderF.map(_ => blockHeaderDAO)
  }

  def destroyHeaderTable(): Future[Unit] = {
    ChainDbManagement.dropHeaderTable(dbConfig)
  }

  /**
    * Fixture that creates a [[org.bitcoins.chain.models.BlockHeaderTable]]
    * with one row inserted into it, the [[RegTestNetChainParams]]
    * genesis block
    */
  def withBlockHeaderDAO(test: OneArgAsyncTest): FutureOutcome = {
    makeFixture(createBlockHeaderDAO, destroyHeaderTable)(test)
  }

  def createPopulatedBlockHeaderDAO(): Future[BlockHeaderDAO] = {
    val tableSetupF = setupHeaderTable()

    val source =
      scala.io.Source.fromURL(getClass.getResource("/block_headers.json"))
    val arrStr = source.getLines.next
    source.close()

    import org.bitcoins.rpc.serializers.JsonReaders.BlockHeaderReads
    val headersResult = Json.parse(arrStr).validate[Vector[BlockHeader]]

    headersResult match {
      case err: JsError =>
        logger.error(s"Failed to parse headers from block_headers.json: $err")
        Future.failed(new RuntimeException(err.toString))
      case JsSuccess(headers, _) =>
        val dbHeaders = headers.zipWithIndex.map {
          case (header, height) =>
            BlockHeaderDbHelper.fromBlockHeader(height, header)
        }

        @tailrec
        def splitIntoBatches(
            batchSize: Int,
            dbHeaders: Vector[BlockHeaderDb],
            batchesSoFar: Vector[Vector[BlockHeaderDb]]): Vector[
          Vector[BlockHeaderDb]] = {
          if (dbHeaders.isEmpty) {
            batchesSoFar
          } else if (dbHeaders.length < batchSize) {
            batchesSoFar.:+(dbHeaders)
          } else {
            val (batch, nextDbHeaders) = dbHeaders.splitAt(batchSize)
            val nextBatchesSoFar = batchesSoFar.:+(batch)

            splitIntoBatches(batchSize, nextDbHeaders, nextBatchesSoFar)
          }
        }

        val batchedDbHeaders = splitIntoBatches(batchSize = 500,
                                                dbHeaders = dbHeaders,
                                                batchesSoFar = Vector.empty)

        val insertedF = tableSetupF.flatMap { _ =>
          batchedDbHeaders.foldLeft(
            Future.successful[Vector[BlockHeaderDb]](Vector.empty)) {
            case (fut, batch) =>
              fut.flatMap(_ =>
                chainHandler.blockchain.blockHeaderDAO.createAll(batch))
          }
        }

        insertedF.map(_ => blockHeaderDAO)
    }
  }

  def withPopulatedBlockHeaderDAO(test: OneArgAsyncTest): FutureOutcome = {
    makeFixture(createPopulatedBlockHeaderDAO, destroyHeaderTable)(test)
  }

  def createChainHandler(): Future[ChainHandler] = {
    val genesisHeaderF = setupHeaderTableWithGenesisHeader()
    genesisHeaderF.map(_ => chainHandler)
  }

  def withChainHandler(test: OneArgAsyncTest): FutureOutcome = {
    makeFixture(createChainHandler, destroyHeaderTable)(test)
  }

  /** Creates the [[org.bitcoins.chain.models.BlockHeaderTable]] */
  private def setupHeaderTable(): Future[Unit] = {
    ChainDbManagement.createHeaderTable(dbConfig = dbConfig,
                                        createIfNotExists = true)
  }

  //BitcoindChainhandler => Future[Assertion]
  def withBitcoindZmqChainHandler(test: OneArgAsyncTest)(
      implicit system: ActorSystem): FutureOutcome = {
    val instance = BitcoindRpcTestUtil.instance()
    val bitcoindF = {
      val bitcoind = new BitcoindRpcClient(instance)
      bitcoind.start().map(_ => bitcoind)
    }
    val zmqRawBlockUriF: Future[Option[InetSocketAddress]] =
      bitcoindF.map(_ => instance.zmqConfig.rawBlock)

    val chainHandlerTestF: OneArgAsyncTest = {
      toChainHandlerTest(bitcoindF = bitcoindF,
                         zmqRawBlockUriF = zmqRawBlockUriF,
                         test = test)
    }
    withChainHandler(chainHandlerTestF)

  }

  /** Creates the [[org.bitcoins.chain.models.BlockHeaderTable]] and inserts the genesis header */
  private def setupHeaderTableWithGenesisHeader(): Future[BlockHeaderDb] = {
    val tableSetupF = setupHeaderTable()

    val genesisHeaderF = tableSetupF.flatMap(_ =>
      chainHandler.blockchain.blockHeaderDAO.create(genesisHeaderDb))
    genesisHeaderF
  }

  /** Drops the header table and returns the given Future[Assertion] after the table is dropped */
  private def dropHeaderTable(
      testExecutionF: Future[Outcome]): FutureOutcome = {
    val dropTableP = Promise[Unit]()
    testExecutionF.onComplete { _ =>
      ChainDbManagement.dropHeaderTable(dbConfig).foreach { _ =>
        dropTableP.success(())
      }
    }

    val outcomeF = dropTableP.future.flatMap(_ => testExecutionF)

    new FutureOutcome(outcomeF)
  }

  /** Represents a bitcoind instance paired with a chain handler via zmq */
  case class BitcoindChainHandler(
      bitcoindRpc: BitcoindRpcClient,
      chainHandler: ChainHandler)

  /** A helper method to transform a test case
    * that takes in a
    * {{{
    *   BitcoindChainHandler => Future[Assertion]
    * }}}
    * and transforms it to return a test case of type
    * {{{
    *   ChainHandler => Future[Assertion]
    * }}}
    * @param bitcoindF
    * @param zmqRawBlockUriF
    * @param test
    * @return
    */
  //[error] /home/chris/dev/bitcoin-s-core/chain-test/src/test/scala/org/bitcoins/chain/util/ChainUnitTest.scala:151:79: type mismatch;
  //[error]  found   : ChainUnitTest.this.FixtureParam => org.scalatest.FutureOutcome
  //[error]  required: ChainUnitTest.this.OneArgAsyncTest
  //[error]       test: OneArgAsyncTest)(implicit system: ActorSystem): OneArgAsyncTest = {
  //[error]
  private def toChainHandlerTest(
      bitcoindF: Future[BitcoindRpcClient],
      zmqRawBlockUriF: Future[Option[InetSocketAddress]],
      test: OneArgAsyncTest)(implicit system: ActorSystem): OneArgAsyncTest = {
    case chainHandler: ChainHandler =>
      val handleRawBlock: ByteVector => Unit = { bytes: ByteVector =>
        val block = Block.fromBytes(bytes)
        chainHandler.processHeader(block.blockHeader)

        ()
      }

      val zmqSubscriberF = zmqRawBlockUriF.map { uriOpt =>
        val socket = uriOpt.get
        val z =
          new ZMQSubscriber(socket = socket,
                            hashTxListener = None,
                            hashBlockListener = None,
                            rawTxListener = None,
                            rawBlockListener = Some(handleRawBlock))
        z.start()
        Thread.sleep(1000)
        z
      }

      val bitcoindChainHandlerF = for {
        _ <- zmqSubscriberF
        bitcoind <- bitcoindF
      } yield BitcoindChainHandler(bitcoind, chainHandler)

      val testExecutionF = bitcoindChainHandlerF
        .flatMap { bch: BitcoindChainHandler =>
          test(bch.asInstanceOf[FixtureParam]).toFuture
        }

      testExecutionF.onComplete { _ =>
        //kill bitcoind
        bitcoindChainHandlerF.flatMap { bch =>
          BitcoindRpcTestUtil.stopServer(bch.bitcoindRpc)
        }

        //stop zmq
        zmqSubscriberF.map(_.stop)
      }
      new FutureOutcome(testExecutionF)
    case f: FixtureParam =>
      FutureOutcome.failed(s"Did not pass in a BitcoindChainHandler, got ${f}")
  }

  override def afterAll(): Unit = {
    system.terminate()
  }
}