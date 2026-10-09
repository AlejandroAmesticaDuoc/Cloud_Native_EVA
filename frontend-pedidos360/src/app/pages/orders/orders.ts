import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

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
  imports: [FormsModule],
  templateUrl: './orders.html',
  styleUrl: './orders.css'
})
export class Orders implements OnInit {

  private readonly ordersApi = inject(OrdersApiService);
  private readonly catalogApi = inject(CatalogApiService);
  private readonly authApi = inject(AuthApiService);

  orders = signal<Order[]>([]);
  products = signal<Product[]>([]);

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
  }

  loadCurrentUser(): void {
    this.authApi.getCurrentUser().subscribe({
      next: (user) => {
        this.canCreate.set(
          user.roles.some(role =>
            ['ADMIN', 'OPERADOR', 'CLIENTE'].includes(role)
          )
        );

        this.canUpdateStatus.set(
          user.roles.some(role =>
            ['ADMIN', 'OPERADOR'].includes(role)
          )
        );
      },

      error: (error) => {
        console.error(
          'Error obteniendo usuario:',
          error
        );
      }
    });
  }

  loadProducts(): void {
    this.catalogApi.getProducts().subscribe({
      next: (products) => {
        this.products.set(
          products.filter(product => product.active)
        );
      },

      error: (error) => {
        console.error(
          'Error obteniendo catálogo:',
          error
        );
      }
    });
  }

  loadOrders(): void {
    this.loading.set(true);
    this.error.set('');

    this.ordersApi.getOrders().subscribe({
      next: (orders) => {
        this.orders.set(orders);

        orders.forEach(order => {
          this.statusValues[order.id] = order.status;
        });

        this.loading.set(false);
      },

      error: (error) => {
        console.error(
          'Error obteniendo pedidos:',
          error
        );

        this.error.set(
          'No fue posible obtener los pedidos.'
        );

        this.loading.set(false);
      }
    });
  }

  addItem(): void {

    if (
      this.selectedProductId <= 0 ||
      this.selectedQuantity <= 0
    ) {
      return;
    }

    const currentItems = this.newOrderItems();

    const existingItem = currentItems.find(
      item => item.productId === this.selectedProductId
    );

    if (existingItem) {

      this.newOrderItems.set(
        currentItems.map(item =>
          item.productId === this.selectedProductId
            ? {
                ...item,
                quantity:
                  item.quantity + this.selectedQuantity
              }
            : item
        )
      );

    } else {

      this.newOrderItems.set([
        ...currentItems,
        {
          productId: this.selectedProductId,
          quantity: this.selectedQuantity
        }
      ]);
    }

    this.selectedProductId = 0;
    this.selectedQuantity = 1;
  }

  removeItem(productId: number): void {
    this.newOrderItems.set(
      this.newOrderItems().filter(
        item => item.productId !== productId
      )
    );
  }

  createOrder(): void {

    if (this.newOrderItems().length === 0) {
      return;
    }

    this.ordersApi.createOrder({
      items: this.newOrderItems()
    }).subscribe({

      next: () => {

        this.newOrderItems.set([]);
        this.showCreateForm.set(false);

        this.loadOrders();
        this.loadProducts();
      },

      error: (error) => {

        console.error(
          'Error creando pedido:',
          error
        );

        this.error.set(
          'No fue posible crear el pedido.'
        );
      }
    });
  }

  getProductName(productId: number): string {

    const product = this.products().find(
      item => item.id === productId
    );

    return product?.name ?? `Producto #${productId}`;
  }

  getProductPrice(productId: number): number {

    const product = this.products().find(
      item => item.id === productId
    );

    return product?.price ?? 0;
  }

  getNewOrderTotal(): number {

    return this.newOrderItems().reduce(
      (total, item) =>
        total +
        this.getProductPrice(item.productId) *
        item.quantity,
      0
    );
  }

  updateOrderStatus(order: Order): void {

    const status = this.statusValues[order.id];

    if (!status) {
      return;
    }

    this.ordersApi.updateStatus(
      order.id,
      status
    ).subscribe({

      next: () => {
        this.loadOrders();
        this.loadProducts();
      },

      error: (error) => {

        console.error(
          'Error actualizando estado del pedido:',
          error
        );

        this.error.set(
          'No fue posible actualizar el estado del pedido.'
        );
      }
    });
  }
}