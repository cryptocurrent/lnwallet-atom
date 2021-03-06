package com.lightning.walletapp.test

import java.net.{Inet4Address, Inet6Address, InetAddress, InetSocketAddress}

import com.google.common.net.InetAddresses
import com.lightning.walletapp.ln.PerHopPayload
import com.lightning.walletapp.ln.crypto.Sphinx
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, Scalar}
import fr.acinq.eclair.UInt64

import scala.util.Random


class WireSpec {
  def randomKey: PrivateKey = PrivateKey({
    val bin = Array.fill[Byte](32)(0)
    Random.nextBytes(bin)
    bin
  }, compressed = true)

  def randomBytes(size: Int): BinaryData = {
    val bin = new Array[Byte](size)
    Random.nextBytes(bin)
    bin
  }

  def randomSignature: BinaryData = {
    val priv = randomBytes(32)
    val data = randomBytes(32)
    val (r, s) = Crypto.sign(data, PrivateKey(priv, true))
    Crypto.encodeSignatureBCA(r, s)
  }

  def allTests = {
    def bin(size: Int, fill: Byte): BinaryData = Array.fill[Byte](size)(fill)

    def scalar(fill: Byte) = Scalar(bin(32, fill))

    def point(fill: Byte) = Scalar(bin(32, fill)).toPoint

    def publicKey(fill: Byte) = PrivateKey(bin(32, fill), compressed = true).publicKey

    {
      println("encode/decode all kind of IPv6 addresses with ipv6address codec")

      {
        // IPv4 mapped
        val bin = hex"00000000000000000000ffffae8a0b08".toBitVector
        val ipv6 = Inet6Address.getByAddress(null, bin.toByteArray, null)
        val bin2 = ipv6address.encode(ipv6).require
        assert(bin == bin2)
      }

      {
        // regular IPv6 address
        val ipv6 = InetAddresses.forString("1080:0:0:0:8:800:200C:417A").asInstanceOf[Inet6Address]
        val bin = ipv6address.encode(ipv6).require
        val ipv62 = ipv6address.decode(bin).require.value
        assert(ipv6 == ipv62)
      }
    }

    {
      println("encode/decode with rgb codec")

      val color = (47.toByte, 255.toByte, 142.toByte)
      val bin = rgb.encode(color).toOption.get
      assert(bin == hex"2f ff 8e".toBitVector)
      val color2 = rgb.decode(bin).toOption.get.value
      assert(color == color2)
    }

    {
      println("encode/decode all kind of IPv6 addresses with ipv6address codec")

      {
        // IPv4 mapped
        val bin = hex"00000000000000000000ffffae8a0b08".toBitVector
        val ipv6 = Inet6Address.getByAddress(null, bin.toByteArray, null)
        val bin2 = ipv6address.encode(ipv6).require
        assert(bin == bin2)
      }

      {
        // regular IPv6 address
        val ipv6 = InetAddresses.forString("1080:0:0:0:8:800:200C:417A").asInstanceOf[Inet6Address]
        val bin = ipv6address.encode(ipv6).require
        val ipv62 = ipv6address.decode(bin).require.value
        assert(ipv6 == ipv62)
      }
    }

    {
      println("encode/decode with nodeaddress codec")

      {
        val ipv4addr = InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address]
        val nodeaddr = IPv4(ipv4addr, 4231)
        val bin = nodeaddress.encode(nodeaddr).require
        assert(bin == hex"01 C0 A8 01 2A 10 87".toBitVector)
        val nodeaddr2 = nodeaddress.decode(bin).require.value
        assert(nodeaddr == nodeaddr2)
      }
      {
        val ipv6addr = InetAddress.getByAddress(hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toArray).asInstanceOf[Inet6Address]
        val nodeaddr = IPv6(ipv6addr, 4231)
        val bin = nodeaddress.encode(nodeaddr).require
        assert(bin == hex"02 2001 0db8 0000 85a3 0000 0000 ac1f 8001 1087".toBitVector)
        val nodeaddr2 = nodeaddress.decode(bin).require.value
        assert(nodeaddr == nodeaddr2)
      }
    }

    {
      println("encode/decode with signature codec")

      val sig = randomSignature
      val wire = LightningMessageCodecs.signature.encode(sig).toOption.get
      val sig1 = LightningMessageCodecs.signature.decode(wire).toOption.get.value
      assert(sig1 == sig)
    }

    {
      println("encode/decode with scalar codec")

      val value = Scalar(randomBytes(32))
      val wire = LightningMessageCodecs.scalar.encode(value).toOption.get
      assert(wire.length == 256)
      val value1 = LightningMessageCodecs.scalar.decode(wire).toOption.get.value
      assert(value1 == value)
    }

    {
      println("encode/decode with point codec")

      val value = Scalar(randomBytes(32)).toPoint
      val wire = LightningMessageCodecs.point.encode(value).toOption.get
      assert(wire.length == 33 * 8)
      val value1 = LightningMessageCodecs.point.decode(wire).toOption.get.value
      assert(value1 == value)
    }

    {
      println("encode/decode with public key codec")

      val value = PrivateKey(randomBytes(32), true).publicKey
      val wire = LightningMessageCodecs.publicKey.encode(value).toOption.get
      assert(wire.length == 33 * 8)
      val value1 = LightningMessageCodecs.publicKey.decode(wire).toOption.get.value
      assert(value1 == value)
    }

    {
      println("encode/decode with zeropaddedstring codec")

      val c = zeropaddedstring

      {
        val alias = "IRATEMONK"
        val bin = c.encode(alias).toOption.get
        assert(bin == BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.size)(0)))
        val alias2 = c.decode(bin).toOption.get.value
        assert(alias == alias2)
      }

      {
        val alias = "this-alias-is-exactly-32-B-long."
        val bin = c.encode(alias).toOption.get
        assert(bin == BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.size)(0)))
        val alias2 = c.decode(bin).toOption.get.value
        assert(alias == alias2)
      }

      {
        val alias = "this-alias-is-far-too-long-because-we-are-limited-to-32-bytes"
        assert(c.encode(alias).isFailure)
      }
    }

    {
      println("encode/decode all channel messages")

      val open = OpenChannel(randomBytes(32), randomBytes(32), 3, 4, 5, UInt64(6), 7, 8, 9, 10, 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte)
      val accept = AcceptChannel(randomBytes(32), 3, UInt64(4), 5, 6, 7, 8, 9, publicKey(1), point(2), point(3), point(4), point(5), point(6))
      val funding_created = FundingCreated(randomBytes(32), bin(32, 0), 3, randomSignature)
      val funding_signed = FundingSigned(randomBytes(32), randomSignature)
      val funding_locked = FundingLocked(randomBytes(32), point(2))
      val update_fee = UpdateFee(randomBytes(32), 2)
      val shutdown = Shutdown(randomBytes(32), bin(47, 0))
      val closing_signed = ClosingSigned(randomBytes(32), 2, randomSignature)
      val update_add_htlc = UpdateAddHtlc(randomBytes(32), 2, 3, bin(32, 0), 4, bin(Sphinx.PacketLength, 0))
      val update_fulfill_htlc = UpdateFulfillHtlc(randomBytes(32), 2, bin(32, 0))
      val update_fail_htlc = UpdateFailHtlc(randomBytes(32), 2, bin(154, 0))
      val update_fail_malformed_htlc = UpdateFailMalformedHtlc(randomBytes(32), 2, randomBytes(32), 1111)
      val commit_sig = CommitSig(randomBytes(32), randomSignature, randomSignature :: randomSignature :: randomSignature :: Nil)
      val revoke_and_ack = RevokeAndAck(randomBytes(32), scalar(0), point(1))
      val channel_announcement = ChannelAnnouncement(randomSignature, randomSignature, randomSignature, randomSignature, bin(7, 9), Block.BCARegtestForkBlockHash, 1, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
      val node_announcement = NodeAnnouncement(randomSignature, bin(0, 0),  1, randomKey.publicKey, (100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil)
      val channel_update = ChannelUpdate(randomSignature, Block.BCARegtestForkBlockHash, 1, 2, bin(2, 2), 3, 4, 5, 6)
      val announcement_signatures = AnnouncementSignatures(randomBytes(32), 42, randomSignature, randomSignature)
      val ping = Ping(100, BinaryData("01" * 10))
      val pong = Pong(BinaryData("01" * 10))

      val msgs: List[LightningMessage] =
        open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
          update_add_htlc :: update_fulfill_htlc :: update_fail_htlc :: update_fail_malformed_htlc :: commit_sig :: revoke_and_ack ::
          channel_announcement :: node_announcement :: channel_update :: announcement_signatures :: ping :: pong :: Nil

      msgs.foreach { msg => {
          val encoded = lightningMessageCodec.encode(msg)
          val decoded = encoded.flatMap(lightningMessageCodec.decode)
        assert(msg == decoded.toOption.get.value)
        }
      }
    }

    {
      println("encode/decode per-hop payload")
      val payload = PerHopPayload(shortChannelId = 42, amtToForward = 142000, outgoingCltv = 500000)
      val bin = LightningMessageCodecs.perHopPayloadCodec.encode(payload).require
      assert(bin.toByteVector.size == 33)
      val payload1 = LightningMessageCodecs.perHopPayloadCodec.decode(bin).require.value
      assert(payload == payload1)

      // realm (the first byte) should be 0
      val bin1 = bin.toByteVector.update(0, 1)
      try {
        val payload2 = LightningMessageCodecs.perHopPayloadCodec.decode(bin1.toBitVector).require.value
        assert(payload2 == payload1)
      } catch {
        case e: Throwable => assert(true)
      }
    }
  }
}
