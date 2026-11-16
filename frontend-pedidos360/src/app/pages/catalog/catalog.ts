import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { CatalogApiService } from '../../core/services/catalog-api.service';
import { OrdersApiService } from '../../core/services/orders-api.service';
import { AuthApiService } from '../../core/services/auth-api.service';

import {
  Product,
  CreateProductRequest,
  UpdateProductRequest
} from '../../core/models/product.model';
import { Order } from '../../core/models/order.model';

export interface CartItem {
  product: Product;
  quantity: number;
}

@Component({
  selector: 'app-catalog',
  imports: [FormsModule, RouterLink],
  templateUrl: './catalog.html',
  styleUrl: './catalog.css'
})
export class Catalog implements OnInit {

  private readonly catalogApi = inject(CatalogApiService);
  private readonly ordersApi = inject(OrdersApiService);
  private readonly authApi = inject(AuthApiService);

  products = signal<Product[]>([]);
  loading = signal(true);
  error = signal('');
  feedbackMessage = signal('');

  cart = signal<CartItem[]>([]);
  cartOpen = signal(false);
  submittingOrder = signal(false);
  lastCreatedOrder = signal<Order | null>(null);

  stockValues: Record<number, number> = {};
  orderQuantities: Record<number, number> = {};

  isAdmin = signal(false);
  showCreateForm = signal(false);
  editingProductId = signal<number | null>(null);

  cartTotalItems = computed(() =>
    this.cart().reduce((acc, item) => acc + item.quantity, 0)
  );

  cartTotalAmount = computed(() =>
    this.cart().reduce((acc, item) => acc + item.product.price * item.quantity, 0)
  );

  editProduct: UpdateProductRequest = {
    name: '',
    price: 0
  };

  newProduct: CreateProductRequest = {
    name: '',
    price: 0,
    stock: 0
  };

  ngOnInit(): void {
    this.loadProducts();
    this.loadCurrentUser();
  }

  loadCurrentUser(): void {
    this.authApi.getCurrentUser().subscribe({
      next: (user) => {
        const normalizedRoles = (user.roles || []).map(r => r.toUpperCase());
        this.isAdmin.set(normalizedRoles.some(r => r.includes('ADMIN')));
      },
      error: (err) => console.error('No fue posible obtener los roles:', err)
    });
  }

  loadProducts(): void {
    this.loading.set(true);
    this.error.set('');

    this.catalogApi.getProducts().subscribe({
      next: (products) => {
        this.products.set(products);

        products.forEach(product => {
          this.stockValues[product.id] = product.stock;
          if (!this.orderQuantities[product.id] || this.orderQuantities[product.id] < 1) {
            this.orderQuantities[product.id] = 1;
          }
        });

        this.loading.set(false);
      },
      error: (err) => {
        console.error('Error consumiendo /api/v1/catalog:', err);
        this.error.set('No fue posible obtener el catálogo.');
        this.loading.set(false);
      }
    });
  }

  incrementQty(product: Product): void {
    const current = this.orderQuantities[product.id] || 1;
    const inCart = this.getQuantityInCart(product.id);
    if (current + inCart < product.stock) {
      this.orderQuantities[product.id] = current + 1;
    }
  }

  decrementQty(product: Product): void {
    const current = this.orderQuantities[product.id] || 1;
    if (current > 1) {
      this.orderQuantities[product.id] = current - 1;
    }
  }

  getQuantityInCart(productId: number): number {
    return this.cart().find(i => i.product.id === productId)?.quantity || 0;
  }

  addToCart(product: Product): void {
    if (!product.active || product.stock <= 0) {
      this.error.set('No hay stock disponible para este producto.');
      return;
    }

    const qtyToAdd = Number(this.orderQuantities[product.id]) || 1;
    const existing = this.cart().find(i => i.product.id === product.id);
    const currentInCart = existing ? existing.quantity : 0;

    if (qtyToAdd < 1 || currentInCart + qtyToAdd > product.stock) {
      this.error.set(`Stock insuficiente. Disponible para agregar: ${product.stock - currentInCart} unidades.`);
      return;
    }

    this.error.set('');
    if (existing) {
      this.cart.update(items =>
        items.map(i =>
          i.product.id === product.id
            ? { ...i, quantity: i.quantity + qtyToAdd }
            : i
        )
      );
    } else {
      this.cart.update(items => [...items, { product, quantity: qtyToAdd }]);
    }

    this.orderQuantities[product.id] = 1;
    this.cartOpen.set(true);
    this.notify(`Agregado al carrito: ${qtyToAdd}x ${product.name}`);
  }

  updateCartItemQty(item: CartItem, delta: number): void {
    const nextQty = item.quantity + delta;
    if (nextQty <= 0) {
      this.removeFromCart(item.product.id);
      return;
    }
    if (nextQty > item.product.stock) {
      this.error.set(`El máximo disponible para "${item.product.name}" es ${item.product.stock} unidades.`);
      return;
    }
    this.error.set('');
    this.cart.update(items =>
      items.map(i => i.product.id === item.product.id ? { ...i, quantity: nextQty } : i)
    );
  }

  removeFromCart(productId: number): void {
    this.cart.update(items => items.filter(i => i.product.id !== productId));
  }

  clearCart(): void {
    this.cart.set([]);
  }

  checkoutCart(): void {
    if (this.cart().length === 0) {
      return;
    }

    this.error.set('');
    this.submittingOrder.set(true);

    const payload = {
      items: this.cart().map(item => ({
        productId: item.product.id,
        quantity: item.quantity
      }))
    };

    this.ordersApi.createOrder(payload).subscribe({
      next: (order) => {
        this.submittingOrder.set(false);
        this.lastCreatedOrder.set(order);
        this.cart.set([]);
        this.cartOpen.set(false);
        this.notify(`¡Compra realizada! Boleta / Pedido #${order.id} generado exitosamente.`);
        this.loadProducts();
      },
      error: (err) => {
        this.submittingOrder.set(false);
        console.error('Error al procesar compra:', err);
        this.error.set(err?.error?.message || 'No fue posible completar la compra.');
      }
    });
  }

  createProduct(): void {
    if (
      !this.newProduct.name.trim() ||
      this.newProduct.price <= 0 ||
      this.newProduct.stock < 0
    ) {
      return;
    }

    this.catalogApi.createProduct(this.newProduct).subscribe({
      next: () => {
        this.newProduct = { name: '', price: 0, stock: 0 };
        this.showCreateForm.set(false);
        this.notify('Producto creado correctamente.');
        this.loadProducts();
      },
      error: (err) => {
        console.error('Error creando producto:', err);
        this.error.set('No fue posible crear el producto.');
      }
    });
  }

  updateStock(product: Product): void {
    const stock = this.stockValues[product.id];
    if (stock === undefined || stock < 0) {
      return;
    }

    this.catalogApi.updateStock(product.id, { stock }).subscribe({
      next: () => {
        this.notify(`Stock de "${product.name}" actualizado a ${stock} unidades.`);
        this.loadProducts();
      },
      error: (err) => {
        console.error('Error actualizando stock:', err);
        this.error.set('No fue posible actualizar el stock.');
      }
    });
  }

  startEdit(product: Product): void {
    this.editingProductId.set(product.id);
    this.editProduct = { name: product.name, price: product.price };
  }

  cancelEdit(): void {
    this.editingProductId.set(null);
    this.editProduct = { name: '', price: 0 };
  }

  updateProduct(product: Product): void {
    if (!this.editProduct.name.trim() || this.editProduct.price <= 0) {
      return;
    }

    this.catalogApi.updateProduct(product.id, this.editProduct).subscribe({
      next: () => {
        this.cancelEdit();
        this.notify(`Producto "${product.name}" actualizado.`);
        this.loadProducts();
      },
      error: (err) => {
        console.error('Error actualizando producto:', err);
        this.error.set('No fue posible actualizar el producto.');
      }
    });
  }

  deactivateProduct(product: Product): void {
    if (!confirm(`¿Deseas desactivar "${product.name}"?`)) {
      return;
    }

    this.catalogApi.deactivateProduct(product.id).subscribe({
      next: () => {
        this.notify(`Producto "${product.name}" desactivado.`);
        this.loadProducts();
      },
      error: (err) => {
        console.error('Error desactivando producto:', err);
        this.error.set('No fue posible desactivar el producto.');
      }
    });
  }

  getProductCategory(name: string): string {
    const n = (name || '').toLowerCase();
    if (n.includes('teclado') || n.includes('mouse')) return 'PERIFÉRICOS';
    if (n.includes('monitor')) return 'MONITORES';
    if (n.includes('audífono') || n.includes('audifono')) return 'AUDIO';
    if (n.includes('silla')) return 'MOBILIARIO';
    if (n.includes('hub') || n.includes('usb')) return 'ACCESORIOS';
    return 'HARDWARE';
  }

  getProductDescription(name: string): string {
    const n = (name || '').toLowerCase();
    if (n.includes('teclado')) return 'Teclado ergonómico con switches mecánicos y retroiluminación RGB personalizable.';
    if (n.includes('mouse')) return 'Sensor óptico de alta precisión con conectividad inalámbrica y batería extendida.';
    if (n.includes('monitor')) return 'Panel IPS Full HD de 27 pulgadas con tasa de refresco de 144Hz para máxima fluidez.';
    if (n.includes('audífono') || n.includes('audifono')) return 'Cancelación activa de ruido con sonido envolvente y micrófono integrado.';
    if (n.includes('silla')) return 'Soporte lumbar ergonómico ajustable con apoyabrazos 3D y malla transpirable.';
    if (n.includes('hub') || n.includes('usb')) return 'Adaptador multipuerto 7 en 1 con salida HDMI 4K, lector SD y carga rápida USB-C.';
    return 'Equipamiento tecnológico certificado disponible para despacho inmediato en Pedidos360.';
  }

  getProductImage(name: string): string {
    const n = (name || '').toLowerCase();
    if (n.includes('teclado')) return 'https://images.unsplash.com/photo-1587829741301-dc798b83add3?auto=format&fit=crop&w=700&q=80';
    if (n.includes('mouse')) return 'https://images.unsplash.com/photo-1527864550417-7fd91fc51a46?auto=format&fit=crop&w=700&q=80';
    if (n.includes('monitor')) return 'https://images.unsplash.com/photo-1527443224154-c4a3942d3acf?auto=format&fit=crop&w=700&q=80';
    if (n.includes('audífono') || n.includes('audifono')) return 'https://images.unsplash.com/photo-1505740420928-5e560c06d30e?auto=format&fit=crop&w=700&q=80';
    if (n.includes('silla')) return 'https://images.unsplash.com/photo-1580480055273-228ff5388ef8?auto=format&fit=crop&w=700&q=80';
    if (n.includes('hub') || n.includes('usb')) return 'https://images.unsplash.com/photo-1625842268584-8f3296236761?auto=format&fit=crop&w=700&q=80';
    return 'https://images.unsplash.com/photo-1518770660439-4636190af475?auto=format&fit=crop&w=700&q=80';
  }

  private notify(msg: string): void {
    this.feedbackMessage.set(msg);
    setTimeout(() => this.feedbackMessage.set(''), 5000);
  }
}
