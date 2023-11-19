package EShop.lab3

import EShop.lab2.{TypedCartActor, TypedCheckout}
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TypedCheckoutTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  it should "Send close confirmation to cart" in {
    // Given
    val orderManagerProbe = testKit.createTestProbe[OrderManager.Command]()
    val typedCartProbe    = testKit.createTestProbe[TypedCartActor.Command]()
    val typedCheckout     = testKit.spawn(new TypedCheckout(cartActor = typedCartProbe.ref).start).ref

    // When
    typedCheckout ! TypedCheckout.StartCheckout
    typedCheckout ! TypedCheckout.SelectDeliveryMethod(method = "method")
    typedCheckout ! TypedCheckout.SelectPayment(payment = "payment", orderManagerRef = orderManagerProbe.ref)
    typedCheckout ! TypedCheckout.ConfirmPaymentReceived

    // Then
    orderManagerProbe.expectMessageType[OrderManager.ConfirmPaymentStarted]
    typedCartProbe expectMessage TypedCartActor.ConfirmCheckoutClosed
  }

}
