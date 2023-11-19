package EShop.lab2

import EShop.lab3.{OrderManager, Payment}
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration._
import scala.language.postfixOps

object TypedCheckout {

  sealed trait Data
  case object Uninitialized                               extends Data
  case class SelectingDeliveryStarted(timer: Cancellable) extends Data
  case class ProcessingPaymentStarted(timer: Cancellable) extends Data

  sealed trait Command
  case object StartCheckout                                                                  extends Command
  case class SelectDeliveryMethod(method: String)                                            extends Command
  case object CancelCheckout                                                                 extends Command
  case object ExpireCheckout                                                                 extends Command
  case class SelectPayment(payment: String, orderManagerRef: ActorRef[OrderManager.Command]) extends Command
  case object ExpirePayment                                                                  extends Command
  case object ConfirmPaymentReceived                                                         extends Command

  sealed trait Event
  case object CheckOutClosed                           extends Event
  case class PaymentStarted(paymentRef: ActorRef[Any]) extends Event
}

class TypedCheckout(
  cartActor: ActorRef[TypedCartActor.Command]
) {
  import TypedCheckout._

  val checkoutTimerDuration: FiniteDuration = 1 seconds
  val paymentTimerDuration: FiniteDuration  = 1 seconds

  private def scheduleCheckoutTimer(context: ActorContext[TypedCheckout.Command]): Cancellable = {
    context.system.scheduler.scheduleOnce(
      delay = checkoutTimerDuration,
      runnable = () => context.self ! ExpireCheckout
    )(context.executionContext)
  }

  private def schedulePaymentTimer(context: ActorContext[TypedCheckout.Command]): Cancellable = {
    context.system.scheduler.scheduleOnce(
      delay = paymentTimerDuration,
      runnable = () => context.self ! ExpirePayment
    )(context.executionContext)
  }

  def start: Behavior[TypedCheckout.Command] = Behaviors.receive((context, message) =>
    message match {
      case StartCheckout =>
        selectingDelivery(
          timer = scheduleCheckoutTimer(context = context)
        )
    }
  )

  def selectingDelivery(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receiveMessage {
    case SelectDeliveryMethod(method) =>
      selectingPaymentMethod(
        timer = timer
      )

    case CancelCheckout | ExpireCheckout => cancelled
  }

  def selectingPaymentMethod(timer: Cancellable): Behavior[TypedCheckout.Command] =
    Behaviors.receive((context, message) =>
      message match {
        case SelectPayment(paymentMethod, orderManagerRef) =>
          onSelectPayment(timer, context, paymentMethod, orderManagerRef)

        case CancelCheckout | ExpireCheckout =>
          cartActor ! TypedCartActor.ConfirmCheckoutCancelled
          cancelled
      }
    )

  private def onSelectPayment(timer: Cancellable,
                              context: ActorContext[Command],
                              paymentMethod: String,
                              orderManagerRef: ActorRef[OrderManager.Command]): Behavior[Command] = {
    timer.cancel
    val payment = new Payment(
      method = paymentMethod,
      orderManager = orderManagerRef,
      checkout = context.self,
    )
    val paymentRef = context.spawnAnonymous(payment.start)

    orderManagerRef ! OrderManager.ConfirmPaymentStarted(paymentRef = paymentRef)

    processingPayment(
      timer = schedulePaymentTimer(context = context),
    )
  }

  def processingPayment(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receiveMessage {
    case ConfirmPaymentReceived =>
      cartActor ! TypedCartActor.ConfirmCheckoutClosed
      timer.cancel
      closed

    case CancelCheckout | ExpirePayment =>
      cartActor ! TypedCartActor.ConfirmCheckoutCancelled
      cancelled
  }

  def cancelled: Behavior[TypedCheckout.Command] = Behaviors.receiveMessage(_ => Behaviors.same)

  def closed: Behavior[TypedCheckout.Command] = Behaviors.receiveMessage(_ => Behaviors.same)
}
