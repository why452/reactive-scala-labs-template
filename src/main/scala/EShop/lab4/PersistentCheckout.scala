package EShop.lab4

import EShop.lab2.{TypedCartActor, TypedCheckout}
import EShop.lab3.{OrderManager, Payment}
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration._

class PersistentCheckout {

  import EShop.lab2.TypedCheckout._

  val timerDuration: FiniteDuration = 1.seconds

  def schedule(context: ActorContext[Command]): Cancellable = {
    context.system.scheduler.scheduleOnce(
      delay = timerDuration,
      runnable = () => context.self ! ExpireCheckout
    )(context.executionContext)
  }

  def apply(cartActor: ActorRef[TypedCartActor.Command], persistenceId: PersistenceId): Behavior[Command] =
    Behaviors.setup { context =>
      EventSourcedBehavior(
        persistenceId,
        WaitingForStart,
        commandHandler(context, cartActor),
        eventHandler(context)
      )
    }

  def commandHandler(
                      context: ActorContext[Command],
                      cartActor: ActorRef[TypedCartActor.Command]
                    ): (State, Command) => Effect[Event, State] = (state, command) => {
    state match {
      case WaitingForStart =>
        command match {
          case StartCheckout => Effect.persist(CheckoutStarted)
          case _ => Effect.unhandled
        }

      case SelectingDelivery(_) =>
        command match {
          case SelectDeliveryMethod(method) => Effect.persist(DeliveryMethodSelected(method))
          case CancelCheckout | ExpireCheckout => Effect.persist(CheckoutCancelled)
          case _ => Effect.unhandled
        }

      case SelectingPaymentMethod(_) =>
        command match {
          case SelectPayment(method, orderManagerRef) =>
            val payment = new Payment(
              method = method,
              orderManager = orderManagerRef,
              checkout = context.self,
            )
            val paymentRef = context.spawnAnonymous(payment.start)

            Effect
              .persist(PaymentStarted(payment = paymentRef))
              .thenRun { _ =>
                orderManagerRef ! OrderManager.ConfirmPaymentStarted(paymentRef = paymentRef)
              }

          case CancelCheckout | ExpireCheckout => Effect.persist(CheckoutCancelled)
          case _ => Effect.unhandled
        }

      case ProcessingPayment(_) =>
        command match {
          case TypedCheckout.ConfirmPaymentReceived => Effect.persist(CheckOutClosed)
          case CancelCheckout | ExpireCheckout => Effect.persist(CheckoutCancelled)
          case _ => Effect.unhandled
        }

      case Cancelled =>
        Effect.none

      case Closed =>
        Effect.none
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
    event match {
      case CheckoutStarted           => SelectingDelivery(timer = schedule(context))
      case DeliveryMethodSelected(_) => SelectingPaymentMethod(timer = state.timerOpt.get)
      case PaymentStarted(_)         => ProcessingPayment(timer = state.timerOpt.get)
      case CheckOutClosed            =>
        state.timerOpt.foreach(_.cancel())
        Closed
      case CheckoutCancelled         => Cancelled
    }
  }
}