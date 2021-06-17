package sttp.tapir.server.vertx.interpreters

import io.vertx.core.Handler
import io.vertx.ext.web.{Route, Router, RoutingContext}
import sttp.capabilities.zio.ZioStreams
import sttp.tapir.Endpoint
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interpreter.{BodyListener, ServerInterpreter}
import sttp.tapir.server.vertx.VertxZioServerInterpreter.{RioFromVFuture, monadError}
import sttp.tapir.server.vertx.decoders.{VertxRequestBody, VertxServerRequest}
import sttp.tapir.server.vertx.encoders.{VertxOutputEncoders, VertxToResponseBody}
import sttp.tapir.server.vertx.routing.PathMapping.extractRouteDefinition
import sttp.tapir.server.vertx.streams.zio._
import sttp.tapir.server.vertx.{VertxBodyListener, VertxZioServerOptions}
import zio._

import scala.reflect.ClassTag

trait VertxZioServerInterpreter[R] extends CommonServerInterpreter {

  def vertxZioServerOptions: VertxZioServerOptions[RIO[R, *]] = VertxZioServerOptions.default

  def route[I, E, O](e: Endpoint[I, E, O, ZioStreams])(logic: I => ZIO[R, E, O])(implicit
      runtime: Runtime[R]
  ): Router => Route =
    route(ServerEndpoint[I, E, O, ZioStreams, RIO[R, *]](e, _ => logic(_).either))

  def route[I, E, O](e: ServerEndpoint[I, E, O, ZioStreams, RIO[R, *]])(implicit
      runtime: Runtime[R]
  ): Router => Route = { router =>
    mountWithDefaultHandlers(e)(router, extractRouteDefinition(e.endpoint))
      .handler(endpointHandler(e))
  }

  def routeRecoverErrors[I, E, O](e: Endpoint[I, E, O, ZioStreams])(
      logic: I => RIO[R, O]
  )(implicit
      eIsThrowable: E <:< Throwable,
      eClassTag: ClassTag[E],
      runtime: Runtime[R]
  ): Router => Route =
    route(e.serverLogicRecoverErrors(logic))

  private def endpointHandler[I, E, O, A](
      e: ServerEndpoint[I, E, O, ZioStreams, RIO[R, *]]
  )(implicit runtime: Runtime[R]): Handler[RoutingContext] = { rc =>
    val fromVFuture = new RioFromVFuture[R]
    implicit val bodyListener: BodyListener[RIO[R, *], RoutingContext => Unit] = new VertxBodyListener[RIO[R, *]]
    val interpreter = new ServerInterpreter[ZioStreams, RIO[R, *], RoutingContext => Unit, ZioStreams](
      new VertxRequestBody[RIO[R, *], ZioStreams](rc, vertxZioServerOptions, fromVFuture),
      new VertxToResponseBody(vertxZioServerOptions),
      vertxZioServerOptions.interceptors,
      vertxZioServerOptions.deleteFile
    )
    val serverRequest = new VertxServerRequest(rc)

    val result = interpreter(serverRequest, e)
      .flatMap {
        case None           => fromVFuture(rc.response.setStatusCode(404).end())
        case Some(response) => Task.succeed(VertxOutputEncoders(response).apply(rc))
      }
      .catchAll { e =>
        RIO.effect(rc.fail(e))
      }

    val canceler = runtime.unsafeRunAsyncCancelable(result) { _ => () }
    rc.response.exceptionHandler { _ =>
      canceler(Fiber.Id.None)
      ()
    }
    ()
  }
}
