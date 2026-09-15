import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

import {
  Product,
  CreateProductRequest,
  UpdateProductRequest,
  UpdateProductStockRequest
} from '../models/product.model';

@Injectable({
  providedIn: 'root'
})
export class CatalogApiService {

  private readonly http = inject(HttpClient);

  private readonly baseUrl =
    `${environment.api.baseUrl}/catalog`;

  getProducts(): Observable<Product[]> {
    return this.http.get<Product[]>(
      this.baseUrl
    );
  }

  getProduct(id: number): Observable<Product> {
    return this.http.get<Product>(
      `${this.baseUrl}/${id}`
    );
  }

  createProduct(
    request: CreateProductRequest
  ): Observable<Product> {

    return this.http.post<Product>(
      this.baseUrl,
      request
    );
  }

  updateProduct(
    id: number,
    request: UpdateProductRequest
  ): Observable<Product> {

    return this.http.put<Product>(
      `${this.baseUrl}/${id}`,
      request
    );
  }

  updateStock(
    id: number,
    request: UpdateProductStockRequest
  ): Observable<Product> {

    return this.http.patch<Product>(
      `${this.baseUrl}/${id}/stock`,
      request
    );
  }

  deactivateProduct(
    id: number
  ): Observable<void> {

    return this.http.delete<void>(
      `${this.baseUrl}/${id}`
    );
  }
}