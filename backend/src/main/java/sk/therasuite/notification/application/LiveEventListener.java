package sk.therasuite.notification.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Odošle udalosť až po úspešnom COMMIT; pri zrušenej transakcii sa nepošle nič. */
@Component
public class LiveEventListener {

  private final EventBroadcaster broadcaster;

  public LiveEventListener(EventBroadcaster broadcaster) {
    this.broadcaster = broadcaster;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onPlanChanged(LiveEvent.PlanChanged event) {
    broadcaster.broadcast(event);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onBusinessDayChanged(LiveEvent.BusinessDayChanged event) {
    broadcaster.broadcast(event);
  }
}
