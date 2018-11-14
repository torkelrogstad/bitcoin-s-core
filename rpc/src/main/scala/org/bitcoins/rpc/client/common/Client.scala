package org.bitcoins.rpc.client.common

import java.util.UUID

import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import org.bitcoins.core.config.NetworkParameters
import org.bitcoins.core.crypto.ECPrivateKey
import org.bitcoins.core.util.BitcoinSLogger
import org.bitcoins.rpc.config.BitcoindInstance
import org.bitcoins.rpc.serializers.BitcoindJsonSerializers._
import org.slf4j.Logger
import play.api.libs.json._

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.sys.process._
import scala.util.Try

trait Client {
  val version: BitcoindVersion
  protected val instance: BitcoindInstance

  protected implicit val executor: ExecutionContext
  protected implicit val materializer: ActorMaterializer
  protected implicit val network: NetworkParameters = instance.network

  /**
   * This is here (and not in BitcoindJsonWrriters)
   * so that the implicit network val is accessible
   */
  implicit object ECPrivateKeyWrites extends Writes[ECPrivateKey] {
    override def writes(o: ECPrivateKey): JsValue = JsString(o.toWIF(network))
  }

  implicit val eCPrivateKeyWrites: Writes[ECPrivateKey] = ECPrivateKeyWrites
  implicit val importMultiAddressWrites: Writes[RpcOpts.ImportMultiAddress] =
    Json.writes[RpcOpts.ImportMultiAddress]
  implicit val importMultiRequestWrites: Writes[RpcOpts.ImportMultiRequest] =
    Json.writes[RpcOpts.ImportMultiRequest]
  private val resultKey: String = "result"
  private val errorKey: String = "error"

  def getDaemon: BitcoindInstance = instance

  def start(): String = {

    if (version != BitcoindVersion.Unknown) {
      val foundVersion = Seq("bitcoind", "--version")
        .!!
        .split("\n")
        .head
        .split(" ")
        .last

      if (!foundVersion.startsWith(version.toString)) {
        throw new RuntimeException(s"Wrong version for bitcoind RPC client! Expected $version, got $foundVersion")
      }
    }

    val cmd = Seq(
      "bitcoind",
      "-datadir=" + instance.authCredentials.datadir,
      "-rpcport=" + instance.rpcUri.getPort,
      "-port=" + instance.uri.getPort)
    cmd.!!
  }

  def isStarted: Boolean = {
    val request = buildRequest(instance, "ping", JsArray.empty)
    val responseF = sendRequest(request)

    val payloadF: Future[JsValue] = responseF.flatMap(getPayload)

    // Ping successful if no error can be parsed from the payload
    val result = Try(Await.result(payloadF.map { payload =>
      (payload \ errorKey).validate[RpcError] match {
        case _: JsSuccess[RpcError] => false
        case _: JsError => true
      }
    }, 2.seconds))

    result.getOrElse(false)
  }

  protected def bitcoindCall[T](
    command: String,
    parameters: List[JsValue] = List.empty)(
    implicit
    reader: Reads[T]): Future[T] = {

    val request = buildRequest(instance, command, JsArray(parameters))
    val responseF = sendRequest(request)

    val payloadF: Future[JsValue] = responseF.flatMap(getPayload)

    payloadF.map { payload =>
      {

        /**
         * These lines are handy if you want to inspect what's being sent to and
         * returned from bitcoind before it's parsed into a Scala type. However,
         * there will sensitive material in some of those calls (private keys,
         * XPUBs, balances, etc). It's therefore not a good idea to enable
         * this logging in production.
         */
        logger.error(s"Command: $command ${parameters.map(_.toString).mkString(" ")}")
        logger.error(s"Payload: \n${Json.prettyPrint(payload)}")
        parseResult((payload \ resultKey).validate[T], payload)
      }
    }
  }

  protected def buildRequest(
    instance: BitcoindInstance,
    methodName: String,
    params: JsArray): HttpRequest = {
    val uuid = UUID.randomUUID().toString

    val m: Map[String, JsValue] = Map(
      "method" -> JsString(methodName),
      "params" -> params,
      "id" -> JsString(uuid))

    val jsObject = JsObject(m)

    logger.debug(s"json rpc request: $m")

    // Would toString work?
    val uri = "http://" + instance.rpcUri.getHost + ":" + instance.rpcUri.getPort
    val username = instance.authCredentials.username
    val password = instance.authCredentials.password
    HttpRequest(
      method = HttpMethods.POST,
      uri,
      entity = HttpEntity(ContentTypes.`application/json`, jsObject.toString()))
      .addCredentials(
        HttpCredentials.createBasicHttpCredentials(username, password))
  }

  protected def logger: Logger = BitcoinSLogger.logger

  protected def sendRequest(req: HttpRequest): Future[HttpResponse] = {
    Http(materializer.system).singleRequest(req)
  }

  protected def getPayload(response: HttpResponse): Future[JsValue] = {
    val payloadF = response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)

    payloadF.map { payload =>
      Json.parse(payload.decodeString(ByteString.UTF_8))
    }
  }

  // Should both logging and throwing be happening?
  private def parseResult[T](result: JsResult[T], json: JsValue): T = {
    checkUnitError[T](result, json)

    result match {
      case res: JsSuccess[T] => res.value
      case res: JsError =>
        (json \ errorKey).validate[RpcError] match {
          case err: JsSuccess[RpcError] =>
            logger.error(s"Error ${err.value.code}: ${err.value.message}")
            throw new RuntimeException(
              s"Error ${err.value.code}: ${err.value.message}")
          case _: JsError =>
            logger.error(JsError.toJson(res).toString())
            throw new IllegalArgumentException(
              s"Could not parse JsResult: ${(json \ resultKey).get}")
        }
    }
  }

  // Catches errors thrown by calls with Unit as the expected return type (which isn't handled by UnitReads)
  private def checkUnitError[T](result: JsResult[T], json: JsValue): Unit = {
    if (result == JsSuccess(())) {
      (json \ errorKey).validate[RpcError] match {
        case err: JsSuccess[RpcError] =>
          logger.error(s"Error ${err.value.code}: ${err.value.message}")
          throw new RuntimeException(
            s"Error ${err.value.code}: ${err.value.message}")
        case _: JsError =>
      }
    }
  }

  case class RpcError(code: Int, message: String)

  implicit val rpcErrorReads: Reads[RpcError] = Json.reads[RpcError]

}