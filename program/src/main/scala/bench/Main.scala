package bench

import scala.concurrent.duration.*
import cats.effect.*
import cats.*
import cats.implicits.*
import fs2.grpc.syntax.all.*
import bench.proto.sample.sample
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.*
import fs2.concurrent.*
import cats.effect.std.Queue
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import scala.concurrent.duration.FiniteDuration
import fs2.io.net.Network
import com.comcast.ip4s.Port
import fs2.io.net.Datagram
import com.comcast.ip4s.*
import fs2.Chunk
import cats.effect.std.Dispatcher
import java.net.InetSocketAddress

object Main extends IOApp {
  val ipaddr = ip"127.0.0.1"
  val hostname = Hostname.fromString(ipaddr.toString).get
  val packets = 10
  val newConn = 10
  val sz = 1024

  def tcpServer: IO[Unit] =
    Network[IO]
      .server(address = host"0.0.0.0".some, port = Some(port"9997"))
      .map(sock => sock.reads.through(sock.writes).attempt)
      .parJoinUnbounded
      .compile
      .drain

  trait Send {
    def sendBytes(chunk: fs2.Chunk[Byte]): IO[Unit]

    def recv(n: Int): IO[fs2.Chunk[Byte]]
  }

  def tcpClient: Resource[IO, Send] =
    Network[IO]
      .client(SocketAddress(hostname, port"9997"))
      .map { sock =>
        new Send {
          def sendBytes(chunk: Chunk[Byte]): IO[Unit] =
            sock.write(chunk)
          def recv(n: Int): IO[Chunk[Byte]] =
            sock.read(n).map(_.get)
        }
      }

  def udpServer: Resource[IO, Unit] =
    Network[IO]
      .openDatagramSocket(address= host"0.0.0.0".some,port = Some(port"9998"))
      .evalMap { sock =>
        sock.reads
          // .evalTap(dg => IO.println(s"Received ${dg.bytes.size} bytes from ${dg.remote}"))
          .through(sock.writes)
          .compile
          .drain
      }

  def updClient0: Resource[IO, Send] =
    Network[IO]
      .openDatagramSocket()
      .map { sock =>
        new Send {
          def sendBytes(chunk: Chunk[Byte]): IO[Unit] =
            sock.write(Datagram(SocketAddress(ipaddr, port"9998"), chunk))
          def recv(n: Int): IO[Chunk[Byte]] =
            sock.reads
              .map(_.bytes)
              // .evalTap(bytes => IO.println(s"Received ${bytes.size} bytes"))
              .unchunks
              .take(n)
              .chunks
              .compile
              .foldMonoid
        }
      }

  def testUdpClient =
    for {
      _ <- IO.println(s"warming up UDP client with ${packets} packets of size ${sz}, ${newConn} times...")
      chnk <- fs2.Chunk.seq((0 to sz).toList.map(_.toByte)).pure[IO]
      packetChnks = (0 until packets).toList.as(chnk)
      runTest = updClient0.use { send =>
        for {
          start <- IO.monotonic
          _ <- packetChnks.traverse(send.sendBytes)
          out <- IO
            .race(
              packetChnks.traverse_(p => send.recv(p.size)) >> IO.monotonic.map(end => (end - start).some),
              IO.sleep(250.millis).as(None)
            )
            .map(_.merge)
        } yield out
      }
      _ <- runTest.replicateA(newConn)
      _ <- IO.println(s"done warming up UDP client, running tests with same parameters")
      results <- runTest.replicateA(newConn)
      report = {
        val lost = results.count(_.isEmpty)
        val nonLost = results.collect { case Some(dur) => dur }
        val durNanos = nonLost.map(_.toNanos.toDouble / 1_000_000)
        val avg = durNanos.sum / nonLost.size
        val avg1 = avg / packets
        val loss = if (lost == 0) 0 else (newConn.toFloat / lost.toFloat)
        s"""|Did $newConn runs of UDP client
            |Packet loss for both ways (send and receive) was ${lost} out of $newConn (${loss * 100}% loss)
            |Average time for packets ($packets) round trip was $avg ms (${avg / 2} ms for one-way)
            |Average time for one packet round trip $avg1 ms (${avg1 / 2} ms for one-way)""".stripMargin
      }
    } yield report

  def runTcpClient =
    for {
      _ <- IO.println(s"warming up TCP client with $packets packets of size ${sz}, ${newConn} times...")
      chnk <- fs2.Chunk.seq((0 to sz).toList.map(_.toByte)).pure[IO]
      packetChnks = (0 until packets).toList.as(chnk)
      runTest = tcpClient.use { send =>
        for {
          start <- IO.monotonic
          _ <- packetChnks.traverse(send.sendBytes)
          out <- packetChnks.traverse_(p => send.recv(p.size)) >> IO.monotonic.map(end => (end - start))
        } yield out
      }
      _ <- runTest.replicateA(newConn)
      _ <- IO.println(s"done warming up UDP client, running tests with same parameters")
      results <- runTest.replicateA(newConn)
      report = {
        val durNanos = results.map(_.toNanos.toDouble / 1_000_000)
        val avg = durNanos.sum / results.size
        val avg1 = avg / packets
        s"""|Did $newConn runs of TCP client
            |Average time for packets ($packets) round trip was $avg ms (${avg / 2} ms for one-way)
            |Average time for one packet round trip $avg1 ms (${avg1 / 2} ms for one-way)""".stripMargin
      }
    } yield report

  def runServer = for {
    q <- Resource.eval(Queue.unbounded[IO, String])
    rsc <- sample.SvcFs2Grpc.bindServiceResource[IO] {
      new sample.SvcFs2Grpc[IO, Metadata] {
        def getSample(request: com.google.protobuf.empty.Empty, ctx: Metadata): fs2.Stream[IO, sample.Sample] =
          fs2.Stream.fromQueueUnterminated(q).map(sample.Sample(_))

        def sendSample(request: fs2.Stream[IO, sample.Sample], ctx: Metadata): IO[com.google.protobuf.empty.Empty] =
          request.map(_.name).enqueueUnterminated(q).compile.drain.as(com.google.protobuf.empty.Empty())
      }
    }
    _ <- NettyServerBuilder
      .forAddress(InetSocketAddress("0.0.0.0", 9999))
      .addService(rsc)
      .resource[IO]
      .evalMap(x => IO(x.start()))
  } yield ()

  def runClient(disp: Dispatcher[IO], n: Int = 1000): Resource[IO, FiniteDuration] = for {
    ch <- NettyChannelBuilder
      .forAddress(ipaddr.toString, 9999)
      .usePlaintext()
      .resource[IO]
    client = sample.SvcFs2Grpc.stub[IO](disp, ch)

    doneGet <- client.getSample(com.google.protobuf.empty.Empty(), new Metadata()).take(n).compile.drain.background

    q <- Resource.eval(Queue.unbounded[IO, Option[String]])
    doneSend <- client
      .sendSample(fs2.Stream.fromQueueNoneTerminated(q).map(sample.Sample(_)), new Metadata())
      .background

    xs = (0 until n).toList.map(x => x.toString * 128)
    streamEffect = fs2.Stream.emits(xs).covary[IO].enqueueNoneTerminated(q)

    start <- Resource.eval(IO.monotonic)

    _ <- Resource.eval {
      streamEffect.compile.drain *> doneSend *> doneGet
    }

    e <- Resource.eval(IO.monotonic)
  } yield e - start

  def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.unit
      server = args.contains("server")

      _ <- Dispatcher.parallel[IO].use { disp =>
        if (server) {
          IO.unit
          // runServer.useForever &> udpServer.useForever &> tcpServer
        } else {
          val grpc = runClient(disp, 10).use_.replicateA(newConn) *> runClient(disp, 1).use { dur =>
            val millis = dur.toNanos.toDouble / 1_000_000
            IO.println(s"GRPC (TCP) took $millis ms")
          }
          val udp = testUdpClient.flatMap(IO.println)
          val tcp = runTcpClient.flatMap(IO.println)
          grpc *> IO.println("----") *> udp *> IO.println("----") *> tcp
        }
      }

    } yield ExitCode.Success
}
