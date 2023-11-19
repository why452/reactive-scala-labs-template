package EShop.lab3

import EShop.lab2.{Cart, TypedCartActor}
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.AskPattern.Askable
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TypedCartTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  import TypedCartActor._

  //use GetItems command which was added to make test easier
  it should "add item properly" in {
    // Given
    val item          = "item"
    val typedCart     = testKit.spawn(new TypedCartActor().start).ref
    val probe         = testKit.createTestProbe[Cart]()
    val expectedItems = Seq(item)

    // When
    typedCart tell AddItem(item = item)
    typedCart tell GetItems(sender = probe.ref)

    // Then
    probe expectMessage Cart(items = expectedItems)
  }

  it should "be empty after adding and removing the same item" in {
    // Given
    val item          = "item"
    val typedCart     = testKit.spawn(new TypedCartActor().start).ref
    val probe         = testKit.createTestProbe[Cart]()
    val expectedItems = Seq()

    // When
    typedCart tell AddItem(item = item)
    typedCart tell RemoveItem(item = item)
    typedCart tell GetItems(sender = probe.ref)

    // Then
    probe expectMessage Cart(items = expectedItems)
  }

  it should "start checkout" in {
    // Given
    val item              = "item"
    val typedCart         = testKit.spawn(new TypedCartActor().start).ref
    val orderManagerProbe = testKit.createTestProbe[OrderManager.Command]()

    // When
    typedCart tell AddItem(item = item)
    typedCart tell StartCheckout(orderManagerRef = orderManagerProbe.ref)

    // Then
    orderManagerProbe.expectMessageType[OrderManager.ConfirmCheckoutStarted]
  }
}
