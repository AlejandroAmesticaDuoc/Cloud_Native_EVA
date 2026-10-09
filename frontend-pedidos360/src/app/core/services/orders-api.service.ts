import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

import {
  CreateOrderRequest,
  Order,
  OrderStatus,
  UpdateOrderStatusRequest
} from '../models/order.model';

@Injectable({
  providedIn: 'root'
})
export class OrdersApiService {

  private readonly http = inject(HttpClient);

  private readonly baseUrl =
    `${environment.api.baseUrl}/orders`;

  getOrders(): Observable<Order[]> {
    return this.http.get<Order[]>(
      this.baseUrl
    );
  }

  getOrder(id: number): Observable<Order> {
    return this.http.get<Order>(
      `${this.baseUrl}/${id}`
    );
  }

  createOrder(
    request: CreateOrderRequest
  ): Observable<Order> {

    return this.http.post<Order>(
      this.baseUrl,
      request
    );
  }

  updateStatus(
    id: number,
    status: OrderStatus
  ): Observable<Order> {

    const request: UpdateOrderStatusRequest = {
      status
    };

    return this.http.patch<Order>(
      `${this.baseUrl}/${id}/status`,
      request
    );
  }

  cancelOrder(id: number): Observable<void> {
    return this.http.post<void>(
      `${this.baseUrl}/${id}/cancel`,
      {}
    );
  }
}