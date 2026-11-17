package sk.therasuite.notification.application;

/**
 * Rozosiela živé udalosti. Dnes v pamäti jednej inštancie ({@link InMemoryEventBroadcaster}); pri
 * viacerých inštanciách ho nahradí implementácia nad PostgreSQL LISTEN/NOTIFY (HLD kap. 6.4).
 */
public interface EventBroadcaster {

  void broadcast(LiveEvent event);
}
