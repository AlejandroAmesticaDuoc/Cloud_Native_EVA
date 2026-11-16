import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';
import { AuditPage } from '../models/audit.model';

@Injectable({
  providedIn: 'root'
})
export class AuditApiService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.api.baseUrl}/audit`;

  getEvents(afterId = 0, size = 50): Observable<AuditPage> {
    const params = new HttpParams()
      .set('afterId', afterId.toString())
      .set('size', size.toString());
    return this.http.get<AuditPage>(this.baseUrl, { params });
  }

  getOrderEvents(orderId: number, afterId = 0, size = 50): Observable<AuditPage> {
    const params = new HttpParams()
      .set('afterId', afterId.toString())
      .set('size', size.toString());
    return this.http.get<AuditPage>(`${this.baseUrl}/orders/${orderId}`, { params });
  }
}
