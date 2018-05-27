package com.lightning.walletapp.ln

import scala.concurrent._
import scala.concurrent.duration._
import com.lightning.walletapp.ln.wire._
import com.lightning.walletapp.ln.LNParams._
import com.lightning.walletapp.ln.Features._

import rx.lang.scala.{Observable => Obs}
import java.net.{InetSocketAddress, Socket}
import com.lightning.walletapp.ln.Tools.{Bytes, none}
import com.lightning.walletapp.ln.crypto.Noise.KeyPair
import fr.acinq.bitcoin.Crypto.PublicKey
import java.util.concurrent.Executors
import fr.acinq.bitcoin.BinaryData


object ConnectionManager {
  var listeners = Set.empty[ConnectionListener]
  var connections = Map.empty[PublicKey, Worker]

  protected[this] val events = new ConnectionListener {
    override def onMessage(nodeId: PublicKey, msg: LightningMessage) = for (lst <- listeners) lst.onMessage(nodeId, msg)
    override def onOperational(nodeId: PublicKey, their: Init) = for (lst <- listeners) lst.onOperational(nodeId, their)
    override def onTerminalError(nodeId: PublicKey) = for (lst <- listeners) lst.onTerminalError(nodeId)
    override def onIncompatible(nodeId: PublicKey) = for (lst <- listeners) lst.onIncompatible(nodeId)
    override def onDisconnect(nodeId: PublicKey) = for (lst <- listeners) lst.onDisconnect(nodeId)
  }

  def connectTo(ann: NodeAnnouncement) = connections get ann.nodeId match {
    case Some(doneWorker) if doneWorker.work.isCompleted => connections += ann.nodeId -> Worker(ann)
    case Some(strangeWorker) if strangeWorker.savedInit == null => strangeWorker.disconnect
    case Some(okWorker) => events.onOperational(ann.nodeId, okWorker.savedInit)
    case None => connections += ann.nodeId -> Worker(ann)
  }

  // TODO: remove this later
  def temporaryFix(isa: InetSocketAddress) = isa.getHostString match {
    case "213.133.99.89" => new InetSocketAddress("5.9.83.143", isa.getPort)
    case "213.133.103.56" => new InetSocketAddress("5.9.138.164", isa.getPort)
    case _ => isa
  }

  case class Worker(ann: NodeAnnouncement, buffer: Bytes = new Bytes(1024), socket: Socket = new Socket) {
    implicit val context: ExecutionContextExecutor = ExecutionContext fromExecutor Executors.newSingleThreadExecutor
    val handler: TransportHandler = new TransportHandler(KeyPair(nodePublicKey.toBin, nodePrivateKey.toBin), ann.nodeId) {
      def handleEnterOperationalState = handler process Init(globalFeatures = LNParams.globalFeatures, LNParams.localFeatures)
      def handleDecryptedIncomingData(data: BinaryData) = intercept(LightningMessageCodecs deserialize data)
      def handleEncryptedOutgoingData(data: BinaryData) = try socket.getOutputStream write data catch none
      def handleError = { case _ => events onTerminalError ann.nodeId }
    }

    // Used to remove disconnected nodes
    var lastMsg = System.currentTimeMillis
    var savedInit: Init = _

    val work = Future {
      // First blocking connect, then send data
      val isa = temporaryFix(ann.socketAddresses.head)
      socket.connect(isa, 7500)
      handler.init

      while (true) {
        val length = socket.getInputStream.read(buffer, 0, buffer.length)
        if (length < 0) throw new RuntimeException("Connection droppped")
        else handler process BinaryData(buffer take length)
      }
    }

    // Listener may trigger a reconnect after this
    work onComplete { _ => events onDisconnect ann.nodeId }

    def disconnect = try socket.close catch none
    def intercept(message: LightningMessage) = {
      // Update liveness on each incoming msg
      lastMsg = System.currentTimeMillis

      message match {
        case ping: Ping if ping.pongLength > 0 =>
          val response = Pong("00" * ping.pongLength)
          handler process response

        case their: Init =>
          // Save their Init for possible subsequent requests
          val isOk = areSupported(their.localFeatures) && dataLossProtect(their.localFeatures)
          if (isOk) events.onOperational(ann.nodeId, their) else events.onIncompatible(ann.nodeId)
          if (isOk) savedInit = their

        case _ =>
          // Send a message downstream
          events.onMessage(ann.nodeId, message)
      }
    }
  }

  Obs interval 60.seconds foreach { _ =>
    val outdated = System.currentTimeMillis - 1000 * 120
    for (work <- connections.values if work.lastMsg < outdated)
      work.disconnect
  }
}

class ConnectionListener {
  def onMessage(nodeId: PublicKey, msg: LightningMessage): Unit = none
  def onOperational(nodeId: PublicKey, their: Init): Unit = none
  def onTerminalError(nodeId: PublicKey): Unit = none
  def onIncompatible(nodeId: PublicKey): Unit = none
  def onDisconnect(nodeId: PublicKey): Unit = none
}