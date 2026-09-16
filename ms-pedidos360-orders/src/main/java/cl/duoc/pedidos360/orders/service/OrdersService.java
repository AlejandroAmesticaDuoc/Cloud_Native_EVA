package cl.duoc.pedidos360.orders.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import cl.duoc.pedidos360.orders.client.CatalogClient;
import cl.duoc.pedidos360.orders.dto.*;
import cl.duoc.pedidos360.orders.entity.*;
import cl.duoc.pedidos360.orders.exception.*;
import cl.duoc.pedidos360.orders.repository.OrderRepository;
import cl.duoc.pedidos360.orders.security.CurrentUser;
import cl.duoc.pedidos360.orders.messaging.NotificationOutbox;
import cl.duoc.pedidos360.orders.messaging.OrderEventOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrdersService {
    private final OrderRepository repository;
    private final CatalogClient catalog;
    private final TransactionTemplate transactions;
    private final NotificationOutbox notifications;
    private final OrderEventOutbox events;

    public OrdersService(OrderRepository repository, CatalogClient catalog, PlatformTransactionManager manager,
            NotificationOutbox notifications, OrderEventOutbox events) {
        this.repository = repository;
        this.catalog = catalog;
        this.notifications = notifications;
        this.events = events;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> list(CurrentUser user, String customerId) {
        if (!user.managesAll()) {
            if (customerId != null && !user.id().equals(customerId)) throw new ForbiddenOperationException();
            customerId = user.id();
        }
        var orders = customerId == null ? repository.findAllByOrderByIdAsc()
                : repository.findByCustomerIdOrderByIdAsc(customerId);
        return orders.stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(long id, CurrentUser user) {
        PurchaseOrder order = repository.findById(id).orElseThrow(OrderNotFoundException::new);
        user.verifyOwner(order.getCustomerId());
        return response(order);
    }

    public OrderResponse create(CreateOrderRequest request, CurrentUser user, String accessToken, String traceId) {
        if (!user.id().equals(request.customerId())) throw new ForbiddenOperationException();
        var productIds = new HashSet<Long>();
        for (var item : request.items()) {
            if (!productIds.add(item.productId())) throw new IllegalArgumentException("No se permiten productos repetidos");
        }
        List<OrderItem> items = new ArrayList<>();
        for (var item : request.items()) {
            var product = catalog.product(item.productId(), accessToken, traceId);
            items.add(new OrderItem(item.productId(), item.quantity(), product.price()));
        }
        return transactions.execute(tx -> {
            PurchaseOrder order = repository.saveAndFlush(new PurchaseOrder(user.id(), items));
            recordEvents(order, null, user, traceId);
            return response(order);
        });
    }

    public OrderResponse changeStatus(long id, OrderStatus target, CurrentUser user, String traceId) {
        if (!user.managesAll()) throw new ForbiddenOperationException();
        return transition(id, target, user, traceId);
    }

    public void cancel(long id, CurrentUser user, String traceId) {
        transition(id, OrderStatus.CANCELADO, user, traceId);
    }

    private OrderResponse transition(long id, OrderStatus target, CurrentUser user, String traceId) {
        var prepared = Objects.requireNonNull(transactions.execute(tx -> {
            PurchaseOrder order = repository.findLocked(id).orElseThrow(OrderNotFoundException::new);
            user.verifyOwner(order.getCustomerId());
            if (order.getPendingStatus() != null) {
                if (order.getPendingStatus() != target) {
                    throw new OrderConflictException("Hay una operación pendiente; reintenta primero esa misma acción");
                }
                return new Prepared(response(order), true);
            }
            if (order.getStatus() == target) return new Prepared(response(order), false);
            validateTransition(order.getStatus(), target);
            boolean stockRequired = target == OrderStatus.ACEPTADO
                    || (target == OrderStatus.CANCELADO && order.getStatus() == OrderStatus.ACEPTADO);
            if (stockRequired) order.prepare(target);
            else {
                OrderStatus previous = order.getStatus();
                order.complete(target);
                recordEvents(order, previous, user, traceId);
            }
            repository.flush();
            return new Prepared(response(order), stockRequired);
        }));
        if (!prepared.stockRequired()) return prepared.response();

        Outcome outcome = Objects.requireNonNull(transactions.execute(tx -> {
            PurchaseOrder order = repository.findLocked(id).orElseThrow(OrderNotFoundException::new);
            user.verifyOwner(order.getCustomerId());
            if (order.getPendingStatus() == null && order.getStatus() == target) {
                return new Outcome(response(order), false);
            }
            if (order.getPendingStatus() != target) throw new OrderConflictException();
            try {
                if (target == OrderStatus.ACEPTADO) {
                    var items = order.getItems().stream().map(item ->
                            new StockDeductionRequest.Item(item.getProductId(), item.getQuantity())).toList();
                    catalog.deduct(new StockDeductionRequest(order.getId(), items), traceId);
                } else {
                    catalog.release(order.getId(), traceId);
                }
            } catch (StockRejectedException exception) {
                order.clearPending();
                repository.flush();
                return new Outcome(response(order), true);
            }
            OrderStatus previous = order.getStatus();
            order.complete(target);
            recordEvents(order, previous, user, traceId);
            repository.flush();
            return new Outcome(response(order), false);
        }));
        if (outcome.rejected()) throw new OrderConflictException("Catalog rechazó el movimiento; revisa productos y stock");
        return outcome.response();
    }

    private void recordEvents(PurchaseOrder order, OrderStatus previous, CurrentUser user, String traceId) {
        notifications.enqueue(order, traceId);
        events.enqueue(order, previous, user.id(), traceId);
    }

    private void validateTransition(OrderStatus current, OrderStatus target) {
        boolean allowed = switch (current) {
            case CREADO -> target == OrderStatus.ACEPTADO || target == OrderStatus.CANCELADO;
            case ACEPTADO -> target == OrderStatus.EN_PREPARACION || target == OrderStatus.CANCELADO;
            case EN_PREPARACION -> target == OrderStatus.DESPACHADO;
            case DESPACHADO -> target == OrderStatus.ENTREGADO;
            case ENTREGADO, CANCELADO -> false;
        };
        if (!allowed) throw new OrderConflictException("La transición de estado no está permitida");
    }

    private OrderResponse response(PurchaseOrder order) {
        return new OrderResponse(order.getId(), order.getCustomerId(), order.getStatus(), order.getCreatedAt(),
                order.getItems().stream().map(item -> new OrderResponse.Item(
                        item.getProductId(), item.getQuantity(), item.getUnitPrice())).toList(), order.getTotal());
    }

    private record Prepared(OrderResponse response, boolean stockRequired) {}
    private record Outcome(OrderResponse response, boolean rejected) {}
}
