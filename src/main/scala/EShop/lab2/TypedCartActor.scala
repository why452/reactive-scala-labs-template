package EShop.lab2

import EShop.lab3.OrderManager
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration._
import scala.language.postfixOps

object TypedCartActor {

  sealed trait Command

  case class AddItem(item: Any) extends Command

  case class RemoveItem(item: Any) extends Command

  case object ExpireCart extends Command

  case class StartCheckout(orderManagerRef: ActorRef[OrderManager.Command]) extends Command

  case object ConfirmCheckoutCancelled extends Command

  case object ConfirmCheckoutClosed extends Command

  case class GetItems(sender: ActorRef[Cart]) extends Command

  sealed trait Event

  case class CheckoutStarted(checkoutRef: ActorRef[TypedCheckout.Command]) extends Event

  case class ItemAdded(item: Any) extends Event

  case class ItemRemoved(item: Any) extends Event

  case object CartEmptied extends Event

  case object CartExpired extends Event

  case object CheckoutClosed extends Event

  case object CheckoutCancelled extends Event

  sealed abstract class State(val timerOpt: Option[Cancellable]) {
    def cart: Cart
  }

  case object Empty extends State(None) {
    def cart: Cart = Cart.empty
  }

  case class NonEmpty(cart: Cart, timer: Cancellable) extends State(Some(timer))

  case class InCheckout(cart: Cart) extends State(None)
}

class TypedCartActor {

  import TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5 seconds

  private def scheduleTimer(context: ActorContext[TypedCartActor.Command]): Cancellable = {
    context.system.scheduler.scheduleOnce(
      delay = cartTimerDuration,
      runnable = () => context.self ! ExpireCart
    )(context.executionContext)
  }

  def start: Behavior[TypedCartActor.Command] = empty

  def empty: Behavior[TypedCartActor.Command] =
    Behaviors.receive((context, message) =>
      message match {
        case AddItem(item) =>
          nonEmpty(
            cart = Cart.empty addItem item,
            timer = scheduleTimer(context = context)
          )

        case GetItems(sender) =>
          sender ! Cart.empty
          Behaviors.same
      }
    )

  def nonEmpty(cart: Cart, timer: Cancellable): Behavior[TypedCartActor.Command] =
    Behaviors.receive((context, message) =>
      message match {
        case AddItem(item) =>
          timer.cancel
          nonEmpty(
            cart = cart addItem item,
            timer = scheduleTimer(context = context)
          )

        case RemoveItem(item) =>
          onRemoveItem(
            cart = cart,
            item = item,
            timer = timer,
            context = context
          )

        case ExpireCart => empty

        case StartCheckout(orderManagerRef) =>
          val checkout = new TypedCheckout(
            cartActor = context.self
          )
          val checkoutRef = context.spawnAnonymous(checkout.start)
          checkoutRef !  TypedCheckout.StartCheckout
          orderManagerRef ! OrderManager.ConfirmCheckoutStarted(checkoutRef = checkoutRef)
          inCheckout(cart = cart)

        case GetItems(sender) =>
          sender ! cart
          Behaviors.same
      }
    )

  private def onRemoveItem(
    cart: Cart,
    item: Any,
    timer: Cancellable,
    context: ActorContext[Command]
  ): Behavior[Command] = {
    val newCart = cart removeItem item

    def isElementRemoved = cart.size != newCart.size

    if (isElementRemoved) {
      timer.cancel
      newCart.size match {
        case size if size > 0 =>
          nonEmpty(
            cart = newCart,
            timer = scheduleTimer(context = context)
          )
        case size if size == 0 => empty
      }
    } else
      Behaviors.same
  }

  def inCheckout(cart: Cart): Behavior[TypedCartActor.Command] =
    Behaviors.receive((context, message) =>
      message match {
        case ConfirmCheckoutCancelled =>
          nonEmpty(
            cart = cart,
            timer = scheduleTimer(context = context)
          )

        case ConfirmCheckoutClosed => empty
      }
    )
}
