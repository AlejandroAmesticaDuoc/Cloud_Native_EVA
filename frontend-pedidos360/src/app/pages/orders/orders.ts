import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { OrdersApiService } from '../../core/services/orders-api.service';
import { CatalogApiService } from '../../core/services/catalog-api.service';
import { AuthApiService } from '../../core/services/auth-api.service';

import {
  CreateOrderItemRequest,
  Order,
  OrderStatus
} from '../../core/models/order.model';

import { Product } from '../../core/models/product.model';

@Component({
  selector: 'app-orders',
  imports: [FormsModule, RouterLink],
  templateUrl: './orders.html',
  styleUrl: './orders.css'
})
export class Orders implements OnInit {

  private readonly ordersApi = inject(OrdersApiService);
  private readonly catalogApi = inject(CatalogApiService);
  private readonly authApi = inject(AuthApiService);
  private readonly route = inject(ActivatedRoute);

  orders = signal<Order[]>([]);
  products = signal<Product[]>([]);
  searchedBoletaId = '';
  highlightedOrder = signal<Order | null>(null);

  loading = signal(true);
  error = signal('');

  canCreate = signal(false);
  canUpdateStatus = signal(false);

  statusValues: Record<number, OrderStatus> = {};

  readonly orderStatuses: OrderStatus[] = [
    'CREADO',
    'ACEPTADO',
    'EN_PREPARACION',
    'DESPACHADO',
    'ENTREGADO',
    'CANCELADO'
  ];

  showCreateForm = signal(false);
  selectedProductId = 0;
  selectedQuantity = 1;
  newOrderItems = signal<CreateOrderItemRequest[]>([]);

  ngOnInit(): void {
    this.loadCurrentUser();
    this.loadProducts();
    this.loadOrders();

    this.route.queryParams.subscribe(params => {
      if (params['boleta']) {
        this.searchedBoletaId = String(params['boleta']);
        this.searchByBoletaId();
      }
    });
  }

  loadCurrentUser(): void {
    this.authApi.getCurrentUser().subscribe({
      next: (user) => {
        const roles = (user.roles || []).map(r => r.toUpperCase());
        this.canCreate.set(true);
        this.canUpdateStatus.set(
          roles.some(role => role.includes('ADMIN') || role.includes('OPERADOR'))
        );
      },
      error: (err) => console.error('Error obteniendo usuario:', err)
    });
  }

  loadProducts(): void {
    this.catalogApi.getProducts().subscribe({
      next: (products) => this.products.set(products),
      error: (err) => console.error('Error cargando productos:', err)
    });
  }

  loadOrders(): void {
    this.loading.set(true);
    this.error.set('');

    this.ordersApi.getOrders().subscribe({
      next: (orders) => {
        this.orders.set(orders);
        orders.forEach(o => (this.statusValues[o.id] = o.status));
        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error cargando pedidos:', err);
        this.error.set('No fue posible cargar los pedidos.');
        this.loading.set(false);
      }
    });
  }

  searchByBoletaId(): void {
    const id = Number(this.searchedBoletaId.trim());
    if (!id || id <= 0) {
      this.highlightedOrder.set(null);
      this.error.set('');
      return;
    }

    this.error.set('');
    this.ordersApi.getOrder(id).subscribe({
      next: (order) => {
        this.highlightedOrder.set(order);
      },
      error: () => {
        this.highlightedOrder.set(null);
        this.error.set(`No se encontró ningún pedido con el ID de boleta #${id}.`);
      }
    });
  }

  clearBoletaSearch(): void {
    this.searchedBoletaId = '';
    this.highlightedOrder.set(null);
    this.error.set('');
  }

  getProductName(productId: number): string {
    return this.products().find(p => p.id === productId)?.name || `Producto #${productId}`;
  }

  addItem(): void {
    if (!this.selectedProductId || this.selectedQuantity < 1) return;
    this.newOrderItems.update(items => [
      ...items,
      { productId: this.selectedProductId, quantity: this.selectedQuantity }
    ]);
    this.selectedProductId = 0;
    this.selectedQuantity = 1;
  }

  removeItem(index: number): void {
    this.newOrderItems.update(items => items.filter((_, i) => i !== index));
  }

  createOrder(): void {
    if (this.newOrderItems().length === 0) return;
    this.ordersApi.createOrder({ items: this.newOrderItems() }).subscribe({
      next: (created) => {
        this.newOrderItems.set([]);
        this.showCreateForm.set(false);
        this.highlightedOrder.set(created);
        this.searchedBoletaId = String(created.id);
        this.loadOrders();
      },
      error: () => this.error.set('No fue posible crear el pedido.')
    });
  }

  updateStatus(order: Order): void {
    const status = this.statusValues[order.id];
    if (!status) return;
    this.ordersApi.updateStatus(order.id, status).subscribe({
      next: () => this.loadOrders(),
      error: () => this.error.set('No fue posible actualizar el estado del pedido.')
    });
  }

  cancelOrder(order: Order): void {
    this.ordersApi.cancelOrder(order.id).subscribe({
      next: () => this.loadOrders(),
      error: () => this.error.set('No fue posible cancelar el pedido.')
    });
  }
}
