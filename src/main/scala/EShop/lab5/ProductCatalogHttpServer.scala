package EShop.lab5

import akka.Done
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat, RootJsonFormat}

import java.net.URI
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future}

object ProductCatalogHttpServer {
  case class SearchRequest(brand: String, productKeyWords: List[String])
  case class SearchResult(items: List[ProductCatalog.Item])
}

trait ProductCatalogJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val uriFormat: JsonFormat[URI] = new JsonFormat[java.net.URI] {
    override def write(obj: java.net.URI): spray.json.JsValue = JsString(obj.toString)

    override def read(json: JsValue): URI =
      json match {
        case JsString(url) => new URI(url)
        case _ => throw new RuntimeException("Parsing exception")
      }
  }

  implicit val searchRequest: RootJsonFormat[ProductCatalogHttpServer.SearchRequest] = jsonFormat2(
    ProductCatalogHttpServer.SearchRequest
  )
  implicit val item: RootJsonFormat[ProductCatalog.Item] = jsonFormat5(
    ProductCatalog.Item
  )
  implicit val searchResult: RootJsonFormat[ProductCatalogHttpServer.SearchResult] = jsonFormat1(
    ProductCatalogHttpServer.SearchResult
  )
}

class ProductCatalogHttpServer extends ProductCatalogJsonSupport {
  private implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, "ProductCatalog")

  implicit val timeout: Timeout = 3.seconds

  def routes(): Route = {
    path("search") {
      get {
        entity(as[ProductCatalogHttpServer.SearchRequest]) { searchRequest =>
          val listings: Future[Receptionist.Listing] = system.receptionist
            .ask((ref: ActorRef[Receptionist.Listing]) =>
              Receptionist.Find(ProductCatalog.ProductCatalogServiceKey, ref)
            )

          val listingResult: Receptionist.Listing = Await.result(listings, Duration.Inf)

          val items: Future[ProductCatalog.Ack] = listingResult
            .allServiceInstances(ProductCatalog.ProductCatalogServiceKey)
            .head
            .ask((ref: ActorRef[ProductCatalog.Ack]) =>
              ProductCatalog.GetItems(searchRequest.brand, searchRequest.productKeyWords, ref)
            )

          onSuccess(items) {
            case ProductCatalog.Items(items) =>
              complete(ProductCatalogHttpServer.SearchResult(items))
          }
        }
      }
    }
  }

  def start(port: Int): Future[Done] = {
    Http()
      .newServerAt("localhost", port)
      .bind(routes())
    Await.ready(system.whenTerminated, Duration.Inf)
  }
}

object ProductCatalogAkkaHttpServerApp extends App {
  new ProductCatalogHttpServer().start(10001)
}