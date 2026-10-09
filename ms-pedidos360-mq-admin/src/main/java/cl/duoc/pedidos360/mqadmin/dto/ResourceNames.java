package cl.duoc.pedidos360.mqadmin.dto;

/**
 * Reglas de nombres compartidas por los DTO y los parámetros de ruta. Son constantes para poder
 * usarlas dentro de las anotaciones de Bean Validation.
 */
public final class ResourceNames {
    /** Nombre que se puede crear o eliminar: 1 a 255 caracteres y sin el prefijo reservado {@code amq.}. */
    public static final String NAME = "^(?!amq\\.)[A-Za-z0-9][A-Za-z0-9._-]{0,254}$";
    /** Nombre consultable: además acepta los recursos propios del broker ({@code amq.*}). */
    public static final String READABLE_NAME = "^[A-Za-z0-9][A-Za-z0-9._-]{0,254}$";
    /** Clave de binding: segmentos de letras, números, '_' o '-', o los comodines '*' y '#', separados por punto. */
    public static final String BINDING_KEY = "^$|^(?:[A-Za-z0-9_-]+|\\*|#)(?:\\.(?:[A-Za-z0-9_-]+|\\*|#))*$";
    /** Routing key de publicación: como la clave de binding, pero sin comodines y no vacía. */
    public static final String ROUTING_KEY = "^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*$";

    public static final String NAME_RULE = "debe tener entre 1 y 255 caracteres (letras, números, '.', '_' o '-'), "
            + "comenzar con letra o número y no usar el prefijo reservado 'amq.'";
    public static final String READABLE_NAME_RULE = "debe tener entre 1 y 255 caracteres (letras, números, '.', '_' o '-') "
            + "y comenzar con letra o número";
    public static final String BINDING_KEY_RULE = "debe estar formada por segmentos de letras, números, '_' o '-', "
            + "o por los comodines '*' y '#', separados por punto (vacía solo tiene sentido en fanout o headers)";
    public static final String ROUTING_KEY_RULE = "debe estar formada por segmentos de letras, números, '_' o '-' "
            + "separados por punto, sin comodines";

    private ResourceNames() {
    }
}
