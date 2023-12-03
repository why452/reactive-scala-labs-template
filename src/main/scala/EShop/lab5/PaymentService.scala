package EShop.lab5

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}

import scala.concurrent.ExecutionContextExecutor
import scala.util.{Failure, Success}

object PaymentService {

  sealed trait Response
  case object PaymentSucceeded extends Response

  case class PaymentClientError() extends Exception
  case class PaymentServerError() extends Exception

  // actor behavior which needs to be supervised
  // use akka.http.scaladsl.Http to make http based payment request
  // use getUri method to obtain url
  def apply(
    method: String,
    payment: ActorRef[Response]
  ): Behavior[HttpResponse] = Behaviors.setup { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    implicit val executionContext: ExecutionContextExecutor = context.system.executionContext

    Http()
      .singleRequest(HttpRequest(uri = getURI(method)))
      .onComplete {
        case Failure(exception) => throw exception
        case Success(value) => context.self ! value
      }

    Behaviors.receiveMessage {
      case HttpResponse(_: StatusCodes.Success, _, _, _) =>
        payment ! PaymentSucceeded
        Behaviors.stopped

      case HttpResponse(_: StatusCodes.ClientError, _, _, _) =>
        println("Damian")
        throw PaymentClientError()

      case HttpResponse(_: StatusCodes.ServerError, _, _, _) =>
        throw PaymentServerError()
    }
  }

  // remember running PymentServiceServer() before trying payu based payments
  private def getURI(method: String) = method match {
    case "payu"   => "http://127.0.0.1:8080"
    case "paypal" => s"http://httpbin.org/status/408"
    case "visa"   => s"http://httpbin.org/status/200"
    case _        => s"http://httpbin.org/status/404"
  }
}
