package bench

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

object Main extends IOApp {
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
      .forPort(9999)
      .addService(rsc)
      .resource[IO]
      .evalMap(x => IO(x.start()))
  } yield ()

  def runClient(n: Int = 1000): Resource[IO, FiniteDuration] = for {
    ch <- NettyChannelBuilder
      .forAddress("127.0.0.1", 9999)
      .usePlaintext()
      .resource[IO]
    client <- sample.SvcFs2Grpc.stubResource[IO](ch)

    doneGet <- client.getSample(com.google.protobuf.empty.Empty(), new Metadata()).take(n).compile.drain.background

    q <- Resource.eval(Queue.unbounded[IO, Option[String]])
    doneSend <- client
      .sendSample(fs2.Stream.fromQueueNoneTerminated(q).map(sample.Sample(_)), new Metadata())
      .background

    xs = (0 until n).toList.map(x => x.toString)
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
      _ <-
        if (server) (runServer.useForever: IO[Unit])
        else {
          runClient(1000).use_.replicateA(20) *> runClient(1).use { dur =>
            val millis = dur.toNanos.toDouble / 1_000_000
            IO.println(s"took $millis ms, average ${millis / 1} ms")
          }
        }
    } yield ExitCode.Success
}
