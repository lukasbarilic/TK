package sk.therasuite.notification.application;

import org.springframework.stereotype.Component;

/** Rozosielanie v pamäti jednej inštancie backendu. */
@Component
public class InMemoryEventBroadcaster implements EventBroadcaster {

  private final LiveConnections connections;

  public InMemoryEventBroadcaster(LiveConnections connections) {
    this.connections = connections;
  }

  @Override
  public void broadcast(LiveEvent event) {
    connections.dispatch(event);
  }
}
