package cl.duoc.pedidos360.audit.exception;

/** Kafka no confirmó la publicación en audit.timeline. Es transitorio: el evento se reintenta sin perderse. */
public class TimelinePublishException extends RuntimeException {
    public TimelinePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
