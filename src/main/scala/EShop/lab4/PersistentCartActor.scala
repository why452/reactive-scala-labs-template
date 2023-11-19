package EShop.lab4

import EShop.lab2.{Cart, TypedCartActor, TypedCheckout}
import EShop.lab3.OrderManager
import akka.actor.Cancellable
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration._

class PersistentCartActor {

  import EShop.lab2.TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5.seconds

  private def scheduleTimer(context: ActorContext[Command]): Cancellable = {
    context.system.scheduler.scheduleOnce(
      delay = cartTimerDuration,
      runnable = () => context.self ! ExpireCart
    )(context.executionContext)
  }

  def apply(persistenceId: PersistenceId): Behavior[Command] =
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId,
        Empty,
        commandHandler(context),
        eventHandler(context)
      )
    }

  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] =
    (state, command) => {
      state match {
        case Empty =>
          command match {
            case AddItem(item) => Effect.persist(ItemAdded(item))
            case GetItems(sender) =>
              sender ! Cart.empty
              Effect.none
            case _ => Effect.unhandled
          }

        case NonEmpty(cart, _) =>
          command match {
            case AddItem(item)                                             => Effect.persist(ItemAdded(item))
            case RemoveItem(item) if cart.contains(item) && cart.size == 1 => Effect.persist(CartEmptied)
            case RemoveItem(item) if cart.contains(item)                   => Effect.persist(ItemRemoved(item))
            case ExpireCart                                                => Effect.persist(CartExpired)
            case StartCheckout(orderManagerRef) =>
              val checkout = new TypedCheckout(
                cartActor = context.self
              )
              val checkoutRef = context.spawnAnonymous(checkout.start)

              Effect
                .persist(CheckoutStarted(checkoutRef = checkoutRef))
                .thenRun { _ =>
                  checkoutRef ! TypedCheckout.StartCheckout
                  orderManagerRef ! OrderManager.ConfirmCheckoutStarted(checkoutRef = checkoutRef)
                }
            case GetItems(sender) =>
              sender ! state.cart
              Effect.none
            case _ => Effect.unhandled
          }

        case InCheckout(_) =>
          command match {
            case ConfirmCheckoutCancelled             => Effect.persist(CheckoutCancelled)
            case TypedCartActor.ConfirmCheckoutClosed => Effect.persist(CheckoutClosed)
            case _                                    => Effect.unhandled
          }
      }
    }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State =
    (state, event) => {
      state.timerOpt.foreach(_.cancel())
      event match {
        case CheckoutStarted(_)        => InCheckout(cart = state.cart)
        case ItemAdded(item)           => NonEmpty(cart = state.cart addItem item, timer = scheduleTimer(context = context))
        case ItemRemoved(item)         => NonEmpty(cart = Cart.empty removeItem item, timer = scheduleTimer(context = context))
        case CartEmptied | CartExpired => Empty
        case CheckoutClosed            => Empty
        case CheckoutCancelled         => NonEmpty(cart = state.cart, timer = scheduleTimer(context = context))
      }
    }

}
