package org.bitcoins.node.serializers.messages.control

import org.bitcoins.core.protocol.CompactSizeUInt
import org.bitcoins.core.serializers.{RawBitcoinSerializer, RawSerializerHelper}
import org.bitcoins.node.messages.AddrMessage
import org.bitcoins.node.messages.control.AddrMessage
import org.bitcoins.node.util.NetworkIpAddress
import scodec.bits.ByteVector

import scala.annotation.tailrec

/**
  * Created by chris on 6/3/16.
  * Responsible for the serialization and deserialization of AddrMessages
  * https://bitcoin.org/en/developer-reference#addr
  */
trait RawAddrMessageSerializer extends RawBitcoinSerializer[AddrMessage] {

  override def read(bytes: ByteVector): AddrMessage = {
    val ipCount = CompactSizeUInt.parseCompactSizeUInt(bytes)
    val ipAddressBytes = bytes.slice(ipCount.size.toInt, bytes.size)
    val (networkIpAddresses, _) =
      parseNetworkIpAddresses(ipCount, ipAddressBytes)
    AddrMessage(ipCount, networkIpAddresses)
  }

  override def write(addrMessage: AddrMessage): ByteVector = {
    addrMessage.ipCount.bytes ++
      RawSerializerHelper.write(
        ts = addrMessage.addresses,
        serializer = RawNetworkIpAddressSerializer.write)
  }

  /**
    * Parses ip addresses inside of an AddrMessage
    * @param ipCount the number of ip addresses we need to parse from the AddrMessage
    * @param bytes the bytes from which we need to parse the ip addresses
    * @return the parsed ip addresses and the remaining bytes
    */
  private def parseNetworkIpAddresses(
      ipCount: CompactSizeUInt,
      bytes: ByteVector): (Seq[NetworkIpAddress], ByteVector) = {
    @tailrec
    def loop(
        remainingAddresses: BigInt,
        remainingBytes: ByteVector,
        accum: List[NetworkIpAddress]): (Seq[NetworkIpAddress], ByteVector) = {
      if (remainingAddresses <= 0) (accum.reverse, remainingBytes)
      else {
        val networkIpAddress =
          RawNetworkIpAddressSerializer.read(remainingBytes)
        val newRemainingBytes =
          remainingBytes.slice(networkIpAddress.size, remainingBytes.size)
        loop(remainingAddresses - 1,
             newRemainingBytes,
             networkIpAddress :: accum)
      }
    }
    loop(ipCount.num.toInt, bytes, List())
  }
}

object RawAddrMessageSerializer extends RawAddrMessageSerializer