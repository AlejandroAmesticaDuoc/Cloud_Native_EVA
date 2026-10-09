import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { CatalogApiService } from '../../core/services/catalog-api.service';
import { AuthApiService } from '../../core/services/auth-api.service';

import {
  Product,
  CreateProductRequest,
  UpdateProductRequest
} from '../../core/models/product.model';

@Component({
  selector: 'app-catalog',
  imports: [FormsModule],
  templateUrl: './catalog.html',
  styleUrl: './catalog.css'
})
export class Catalog implements OnInit {

  private readonly catalogApi = inject(CatalogApiService);
  private readonly authApi = inject(AuthApiService);

  products = signal<Product[]>([]);
  loading = signal(true);
  error = signal('');
  stockValues: Record<number, number> = {};

  isAdmin = signal(false);
  showCreateForm = signal(false);
  editingProductId = signal<number | null>(null);

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
        this.isAdmin.set(
          user.roles.includes('ADMIN')
        );
      },
      error: (error) => {
        console.error(
          'No fue posible obtener los roles:',
          error
        );
      }
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
        });

        this.loading.set(false);
      },

      error: (error) => {
        console.error(
          'Error consumiendo /api/v1/catalog:',
          error
        );

        this.error.set(
          'No fue posible obtener el catálogo.'
        );

        this.loading.set(false);
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

    this.catalogApi.createProduct(
      this.newProduct
    ).subscribe({

      next: () => {

        this.newProduct = {
          name: '',
          price: 0,
          stock: 0
        };

        this.showCreateForm.set(false);

        this.loadProducts();
      },

      error: (error) => {
        console.error(
          'Error creando producto:',
          error
        );

        this.error.set(
          'No fue posible crear el producto.'
        );
      }
    });
  }

  updateStock(product: Product): void {

    const stock = this.stockValues[product.id];

    if (stock === undefined || stock < 0) {
      return;
    }

    this.catalogApi.updateStock(
      product.id,
      { stock }
    ).subscribe({

      next: () => {
        this.loadProducts();
      },

      error: (error) => {
        console.error(
          'Error actualizando stock:',
          error
        );

        this.error.set(
          'No fue posible actualizar el stock.'
        );
      }
    });
  }

  startEdit(product: Product): void {
    this.editingProductId.set(product.id);

    this.editProduct = {
      name: product.name,
      price: product.price
    };
  }

  cancelEdit(): void {
    this.editingProductId.set(null);

    this.editProduct = {
      name: '',
      price: 0
    };
  }

  updateProduct(product: Product): void {

    if (
      !this.editProduct.name.trim() ||
      this.editProduct.price <= 0
    ) {
      return;
    }

    this.catalogApi.updateProduct(
      product.id,
      this.editProduct
    ).subscribe({

      next: () => {
        this.cancelEdit();
        this.loadProducts();
      },

      error: (error) => {
        console.error(
          'Error actualizando producto:',
          error
        );

        this.error.set(
          'No fue posible actualizar el producto.'
        );
      }
    });
  }

  deactivateProduct(product: Product): void {

    const confirmed = confirm(
      `¿Deseas desactivar "${product.name}"?`
    );

    if (!confirmed) {
      return;
    }

    this.catalogApi.deactivateProduct(
      product.id
    ).subscribe({

      next: () => {
        this.loadProducts();
      },

      error: (error) => {
        console.error(
          'Error desactivando producto:',
          error
        );

        this.error.set(
          'No fue posible desactivar el producto.'
        );
      }
    });
  }
}