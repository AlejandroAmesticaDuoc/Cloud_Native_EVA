package cl.duoc.pedidos360.bff.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-Id";
    private static final String ATTRIBUTE = TraceIdFilter.class.getName();
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    public static String resolve(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String stored) {
            return stored;
        }
        String received = request.getHeader(HEADER);
        String traceId = received != null && SAFE.matcher(received).matches()
                ? received : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        return traceId;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HEADER, resolve(request));
        chain.doFilter(request, response);
    }
}
