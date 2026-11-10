package cl.duoc.pedidos360.audit.messaging;

import java.sql.SQLTransientException;
import cl.duoc.pedidos360.audit.exception.InvalidEventException;
import cl.duoc.pedidos360.audit.exception.TimelinePublishException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Clasificación de fallas del consumidor Kafka usada por el DefaultErrorHandler y el recuperador. */
public final class KafkaFailures {
    private KafkaFailures() {}

    /** Infraestructura no disponible (base de datos o Kafka): se reintenta hasta que vuelva, sin descartar el evento. */
    public static boolean isTransient(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException || cause instanceof DataAccessResourceFailureException
                    || cause instanceof RecoverableDataAccessException || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException || cause instanceof TimelinePublishException
                    || cause instanceof org.apache.kafka.common.errors.RetriableException) {
                return true;
            }
        }
        return false;
    }

    /** Código de rechazo guardado en audit_rejections. */
    public static String reason(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidEventException invalid) return invalid.reason();
            if (cause instanceof DeserializationException) return "INVALID_EVENT";
        }
        return "PROCESSING_FAILED";
    }

    /** Bytes originales del mensaje si no se pudo deserializar; null si el valor llegó como DTO. */
    public static byte[] rawPayload(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof DeserializationException failure) return failure.getData();
        }
        return null;
    }

    public static String describe(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = String.valueOf(root.getMessage()).replaceAll("\\p{Cntrl}", "_");
        return root.getClass().getSimpleName() + ": " + (message.length() > 200 ? message.substring(0, 200) : message);
    }
}
