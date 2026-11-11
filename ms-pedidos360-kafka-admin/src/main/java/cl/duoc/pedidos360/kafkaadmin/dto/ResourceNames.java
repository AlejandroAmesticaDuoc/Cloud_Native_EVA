package cl.duoc.pedidos360.kafkaadmin.dto;

/** Reglas de nombres de Kafka compartidas por los DTO y los parámetros de ruta (constantes para las anotaciones). */
public final class ResourceNames {
    /** Tópico que se puede crear, modificar o eliminar: 1 a 249 caracteres válidos para Kafka y sin el prefijo interno "__". */
    public static final String TOPIC = "^(?!__)(?!\\.{1,2}$)[A-Za-z0-9._-]{1,249}$";
    /** Tópico consultable: además acepta los internos de Kafka (__consumer_offsets...). */
    public static final String READABLE_TOPIC = "^(?!\\.{1,2}$)[A-Za-z0-9._-]{1,249}$";
    /** Consumer group: 1 a 255 caracteres (letras, números, '.', '_' o '-'). */
    public static final String GROUP = "^[A-Za-z0-9._-]{1,255}$";

    public static final String TOPIC_RULE = "debe tener entre 1 y 249 caracteres (letras, números, '.', '_' o '-') "
            + "y no usar el prefijo interno '__'";
    public static final String READABLE_TOPIC_RULE = "debe tener entre 1 y 249 caracteres (letras, números, '.', '_' o '-')";
    public static final String GROUP_RULE = "debe tener entre 1 y 255 caracteres (letras, números, '.', '_' o '-')";

    /** Límites fijos de las anotaciones; el servicio además valida contra el clúster real. */
    public static final int MAX_PARTITIONS = 50;
    public static final int MAX_REPLICATION_FACTOR = 3;

    private ResourceNames() {
    }
}
